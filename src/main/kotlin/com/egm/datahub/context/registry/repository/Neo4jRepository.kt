package com.egm.datahub.context.registry.repository

import org.neo4j.ogm.session.Session
import org.springframework.stereotype.Component

@Component
class Neo4jRepository(
        private val ogmSession: Session
) {

    fun createEntity(jsonld: String) : Long {
        val importStatement = """
            CALL semantics.importRDFSnippet(
                '$jsonld',
                'JSON-LD',
                { handleVocabUris: 'SHORTEN', typesToLabels: true, commitSize: 500 }
            )
        """.trimIndent()

        val queryResults = ogmSession.query(importStatement, emptyMap<String, Any>()).queryResults()
        return queryResults.first()["triplesLoaded"] as Long
    }

    fun getByURI(uri: String) : List<Map<String, Any>> {
        val pattern = "{ uri: '$uri' }"
        val matchQuery = """
            MATCH (n $pattern ) RETURN n
        """.trimIndent()
        val result = ogmSession.query(matchQuery, emptyMap<String, Any>())
        return result.queryResults().toList()
    }


    fun getRelatedEntitiesByLabel(label: String) : List<Map<String, Any>> {
        val matchQuery = """
            MATCH (a)-[b]-(c:$label)  RETURN *
        """.trimIndent()
        val result = ogmSession.query(matchQuery, emptyMap<String, Any>())
        return result.queryResults().toList()
    }

    fun getEntitiesByLabel(label: String) : List<Map<String, Any>> {
        val matchQuery = """
            MATCH (n:$label) RETURN n
        """.trimIndent()
        val result = ogmSession.query(matchQuery, emptyMap<String, Any>())
        return result.queryResults().toList()
    }

}
