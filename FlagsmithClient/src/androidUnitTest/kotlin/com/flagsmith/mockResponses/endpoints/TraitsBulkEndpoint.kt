package com.flagsmith.mockResponses.endpoints

import com.flagsmith.defaultJson
import com.flagsmith.entities.IdentityAndTraits
import com.flagsmith.entities.Trait
import com.flagsmith.entities.TraitWithIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TraitsBulkEndpoint(private val traits: List<Trait>, private val identity: String) :
    PostEndpoint<TraitWithIdentity>(
        path = "/identities/",
        body = defaultJson.encodeToString(
            IdentityAndTraits(
                identifier = identity,
                traits = traits
            )
        ),
    )