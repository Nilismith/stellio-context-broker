package com.egm.stellio.shared.model

import java.net.URI

data class QueryParams(
    val ids: Set<URI> = emptySet(),
    val types: Set<ExpandedTerm> = emptySet(),
    val idPattern: String? = null,
    val q: String? = null,
    val limit: Int,
    val offset: Int,
    val count: Boolean = false,
    val attrs: Set<ExpandedTerm> = emptySet(),
    val includeSysAttrs: Boolean = false,
    val useSimplifiedRepresentation: Boolean = false,
    val geoQuery: GeoQuery? = null,
    val context: String
)
