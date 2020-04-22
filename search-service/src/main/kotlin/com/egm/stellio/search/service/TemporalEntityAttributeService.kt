package com.egm.stellio.search.service

import com.egm.stellio.search.model.AttributeInstance
import com.egm.stellio.search.model.TemporalEntityAttribute
import com.egm.stellio.search.model.RawValue
import com.egm.stellio.search.model.TemporalValue
import com.egm.stellio.search.util.isAttributeOfMeasureType
import com.egm.stellio.search.util.valueToDoubleOrNull
import com.egm.stellio.search.util.valueToStringOrNull
import com.egm.stellio.shared.util.NgsiLdParsingUtils
import com.egm.stellio.shared.util.NgsiLdParsingUtils.NGSILD_ENTITY_TYPE
import com.egm.stellio.shared.util.NgsiLdParsingUtils.NGSILD_OBSERVED_AT_PROPERTY
import com.egm.stellio.shared.util.NgsiLdParsingUtils.NGSILD_PROPERTY_TYPE
import com.egm.stellio.shared.util.NgsiLdParsingUtils.NGSILD_PROPERTY_VALUE
import com.egm.stellio.shared.util.NgsiLdParsingUtils.NGSILD_PROPERTY_VALUES
import com.egm.stellio.shared.util.NgsiLdParsingUtils.expandValueAsMap
import com.egm.stellio.shared.util.NgsiLdParsingUtils.getPropertyValueFromMap
import com.egm.stellio.shared.util.NgsiLdParsingUtils.getPropertyValueFromMapAsDateTime
import io.r2dbc.postgresql.codec.Json
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.data.r2dbc.core.bind
import org.springframework.data.r2dbc.core.isEquals
import org.springframework.data.r2dbc.query.Criteria.where
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.OffsetDateTime

@Service
class TemporalEntityAttributeService(
    private val databaseClient: DatabaseClient,
    private val attributeInstanceService: AttributeInstanceService
) {

    fun create(temporalEntityAttribute: TemporalEntityAttribute): Mono<Int> =
        databaseClient.execute("""
            INSERT INTO temporal_entity_attribute (id, entity_id, type, attribute_name, attribute_value_type, entity_payload)
            VALUES (:id, :entity_id, :type, :attribute_name, :attribute_value_type, :entity_payload)
            """)
            .bind("id", temporalEntityAttribute.id)
            .bind("entity_id", temporalEntityAttribute.entityId)
            .bind("type", temporalEntityAttribute.type)
            .bind("attribute_name", temporalEntityAttribute.attributeName)
            .bind("attribute_value_type", temporalEntityAttribute.attributeValueType.toString())
            .bind("entity_payload", temporalEntityAttribute.entityPayload?.let { Json.of(temporalEntityAttribute.entityPayload) })
            .fetch()
            .rowsUpdated()

    fun addEntityPayload(temporalEntityAttribute: TemporalEntityAttribute, payload: String): Mono<Int> =
        databaseClient.execute("UPDATE temporal_entity_attribute set entity_payload = :entity_payload")
            .bind("entity_payload", Json.of(payload))
            .fetch()
            .rowsUpdated()

    fun createEntityTemporalReferences(payload: String): Mono<Int> {

        val entity = NgsiLdParsingUtils.parseEntity(payload)
        val rawEntity = entity.first

        val temporalProperties = rawEntity
            .filter {
                it.value is List<*>
            }
            .filter {
                // TODO abstract this crap into an NgsiLdParsingUtils function
                val entryValue = (it.value as List<*>)[0]
                if (entryValue is Map<*, *>) {
                    val values = (it.value as List<*>)[0] as Map<String, Any>
                    values.containsKey("https://uri.etsi.org/ngsi-ld/observedAt")
                } else {
                    false
                }
            }

        return Flux.fromIterable(temporalProperties.asIterable())
            .map {
                val expandedValues = expandValueAsMap(it.value)
                val attributeValue = getPropertyValueFromMap(expandedValues, NGSILD_PROPERTY_VALUE)!!
                val attributeValueType =
                    if (isAttributeOfMeasureType(attributeValue))
                        TemporalEntityAttribute.AttributeValueType.MEASURE
                    else
                        TemporalEntityAttribute.AttributeValueType.ANY
                val temporalEntityAttribute = TemporalEntityAttribute(
                    entityId = rawEntity["@id"] as String,
                    type = (rawEntity["@type"] as List<*>)[0] as String,
                    attributeName = it.key,
                    attributeValueType = attributeValueType,
                    entityPayload = payload
                )

                val observedAt = getPropertyValueFromMapAsDateTime(expandedValues, NGSILD_OBSERVED_AT_PROPERTY)!!
                val attributeInstance = AttributeInstance(
                    temporalEntityAttribute = temporalEntityAttribute.id,
                    observedAt = observedAt,
                    measuredValue = valueToDoubleOrNull(attributeValue),
                    value = valueToStringOrNull(attributeValue)
                )

                Pair(temporalEntityAttribute, attributeInstance)
            }
            .flatMap { temporalEntityAttributeAndInstance ->
                create(temporalEntityAttributeAndInstance.first).zipWhen {
                    attributeInstanceService.create(temporalEntityAttributeAndInstance.second)
                }
            }
            .collectList()
            .map { it.size }
    }

    fun getForEntity(id: String, attrs: List<String>): Flux<TemporalEntityAttribute> {
        var criteria = where("entity_id").isEquals(id)
        if (attrs.isNotEmpty())
            criteria = criteria.and("attribute_name").`in`(attrs)

        return databaseClient
            .select()
            .from(TemporalEntityAttribute::class.java)
            .matching(criteria)
            .fetch()
            .all()
    }

    fun getFirstForEntity(id: String): Mono<TemporalEntityAttribute> =
        databaseClient
            .select()
            .from(TemporalEntityAttribute::class.java)
            .matching(where("entity_id").isEquals(id))
            .fetch()
            .first()

    fun getForEntityAndAttribute(id: String, attritbuteName: String): Mono<TemporalEntityAttribute> {
        val criteria = where("entity_id").isEquals(id)
            .and("attribute_name").isEquals(attritbuteName)

        return databaseClient
            .select()
            .from(TemporalEntityAttribute::class.java)
            .matching(criteria)
            .fetch()
            .one()
    }

    fun injectTemporalValues(rawEntity: Pair<Map<String, Any>, List<String>>, rawResults: List<List<Map<String, Any>>>): Pair<Map<String, Any>, List<String>> {

        val entity = rawEntity.first.toMutableMap()
        if (rawResults.size == 1 && rawResults[0][0].isEmpty())
            return Pair(entity, rawEntity.second)

        rawResults.forEach {
            // attribute_name is the name of the temporal property we want to update
            val attributeName = it.first()["attribute_name"]!!
            val expandedAttributeName = NgsiLdParsingUtils.expandJsonLdKey(attributeName as String, rawEntity.second)

            // extract the temporal property from the raw entity and remove the value property from it
            // ... if it exists, which is not the case for notifications of a subscription (in this case, create it)
            val propertyToEnrich: MutableMap<String, Any> =
                if (entity[expandedAttributeName] != null) {
                    expandValueAsMap(entity[expandedAttributeName]!!).toMutableMap()
                } else {
                    mutableMapOf(
                        NGSILD_ENTITY_TYPE to NGSILD_PROPERTY_TYPE.uri
                    )
                }
            propertyToEnrich.remove(NGSILD_PROPERTY_VALUE)

            val valuesMap =
                it.map {
                    if (it["value"] is Double)
                        TemporalValue(it["value"] as Double, (it["observed_at"] as OffsetDateTime).toString())
                    else
                        RawValue(it["value"]!!, (it["observed_at"] as OffsetDateTime).toString())
                }
            propertyToEnrich[NGSILD_PROPERTY_VALUES] = listOf(mapOf("@list" to valuesMap))

            // and finally update the raw entity with the updated temporal property
            entity.remove(expandedAttributeName)
            entity[expandedAttributeName!!] = listOf(propertyToEnrich)
        }

        return Pair(entity, rawEntity.second)
    }
}