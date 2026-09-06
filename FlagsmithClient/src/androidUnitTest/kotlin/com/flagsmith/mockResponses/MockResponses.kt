package com.flagsmith.mockResponses

import com.flagsmith.mockResponses.endpoints.FlagsEndpoint
import com.flagsmith.mockResponses.endpoints.IdentityFlagsAndTraitsEndpoint
import org.mockserver.integration.ClientAndServer
import org.mockserver.matchers.Times
import org.mockserver.model.HttpError
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType

enum class MockEndpoint(val path: String, val body: String) {
    GET_IDENTITIES(IdentityFlagsAndTraitsEndpoint("").path, MockResponses.getIdentities),
    GET_FLAGS(FlagsEndpoint.path, MockResponses.getFlags),
    GET_TRANSIENT_IDENTITIES(IdentityFlagsAndTraitsEndpoint("").path, MockResponses.getTransientIdentities),
    GET_IDENTITIES_TRAIT_STRING(
        IdentityFlagsAndTraitsEndpoint("").path,
        MockResponses.getTraitString
    ),
    GET_IDENTITIES_TRAIT_INTEGER(
        IdentityFlagsAndTraitsEndpoint("").path,
        MockResponses.getTraitInteger
    ),
    GET_IDENTITIES_TRAIT_DOUBLE(
        IdentityFlagsAndTraitsEndpoint("").path,
        MockResponses.getTraitDouble
    ),
    GET_IDENTITIES_TRAIT_BOOLEAN(
        IdentityFlagsAndTraitsEndpoint("").path,
        MockResponses.getTraitBoolean
    ),
}

fun ClientAndServer.mockResponseFor(endpoint: MockEndpoint) {
    `when`(request().withPath(endpoint.path), Times.once())
        .respond(
            response()
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(endpoint.body)
        )
}

fun ClientAndServer.mockResponseFor(path: String, body: String) {
    `when`(request().withPath(path), Times.once())
        .respond(
            response()
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(body)
        )
}

fun ClientAndServer.mockFailureFor(endpoint: MockEndpoint) {
    `when`(request().withPath(endpoint.path), Times.once())
        .respond(
            response()
                .withStatusCode(500)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("{error: \"Internal Server Error\"}")
        )
    Times.once()
}

fun ClientAndServer.mockDropConnection(endpoint: MockEndpoint) {
    `when`(request().withPath(endpoint.path), Times.once())
        .error(
            HttpError.error()
                .withDropConnection(true)
        )

}

object MockResponses {
    val getIdentities = """
        {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            },
            {
              "feature_state_value": 756,
              "feature": {
                "type": "STANDARD",
                "name": "with-value",
                "id": 35507
              },
              "enabled": true
            },
            {
              "feature_state_value": "",
              "feature": {
                "type": "STANDARD",
                "name": "with-value-just-person-enabled",
                "id": 35508
              },
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_value": "12345",
              "trait_key": "set-from-client",
              "transient": false
            },
            {
              "trait_value": "electric pink",
              "trait_key": "favourite-colour",
              "transient": false
            }
          ]
        }
    """.trimIndent()

    val getTransientIdentities = """
        {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            }
          ],
          "traits": []
        }
    """.trimIndent()

    val getFlags = """
        [
          {
            "enabled": true,
            "feature": {
              "type": "STANDARD",
              "id": 35506,
              "name": "no-value"
            },
            "feature_state_value": null
          },
          {
            "enabled": true,
            "feature": {
              "type": "STANDARD",
              "id": 35507,
              "name": "with-value"
            },
            "feature_state_value": 7
          },
          {
            "enabled": false,
            "feature": {
              "type": "STANDARD",
              "id": 35508,
              "name": "with-value-just-person-enabled"
            },
            "feature_state_value": null
          }
        ]
    """.trimIndent()

    val getTraitString = """
        {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            },
            {
              "feature_state_value": 756,
              "feature": {
                "type": "STANDARD",
                "name": "with-value",
                "id": 35507
              },
              "enabled": true
            },
            {
              "feature_state_value": "",
              "feature": {
                "type": "STANDARD",
                "name": "with-value-just-person-enabled",
                "id": 35508
              },
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_value": "12345",
              "trait_key": "client-key"
            },
            {
              "trait_value": "electric pink",
              "trait_key": "favourite-colour"
            }
          ]
        }
    """.trimIndent()

    val getTraitInteger = """
        {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            },
            {
              "feature_state_value": 756,
              "feature": {
                "type": "STANDARD",
                "name": "with-value",
                "id": 35507
              },
              "enabled": true
            },
            {
              "feature_state_value": "",
              "feature": {
                "type": "STANDARD",
                "name": "with-value-just-person-enabled",
                "id": 35508
              },
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_value": 5,
              "trait_key": "client-key"
            },
            {
              "trait_value": "electric pink",
              "trait_key": "favourite-colour"
            }
          ]
        }
    """.trimIndent()

    val getTraitDouble = """
        {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            },
            {
              "feature_state_value": 756,
              "feature": {
                "type": "STANDARD",
                "name": "with-value",
                "id": 35507
              },
              "enabled": true
            },
            {
              "feature_state_value": "",
              "feature": {
                "type": "STANDARD",
                "name": "with-value-just-person-enabled",
                "id": 35508
              },
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_value": 0.5,
              "trait_key": "client-key"
            },
            {
              "trait_value": "electric pink",
              "trait_key": "favourite-colour"
            }
          ]
        }
    """.trimIndent()

    val getTraitBoolean = """
       {
          "flags": [
            {
              "feature_state_value": null,
              "feature": {
                "type": "STANDARD",
                "name": "no-value",
                "id": 35506
              },
              "enabled": true
            },
            {
              "feature_state_value": 756,
              "feature": {
                "type": "STANDARD",
                "name": "with-value",
                "id": 35507
              },
              "enabled": true
            },
            {
              "feature_state_value": "",
              "feature": {
                "type": "STANDARD",
                "name": "with-value-just-person-enabled",
                "id": 35508
              },
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_value": true,
              "trait_key": "client-key"
            },
            {
              "trait_value": "electric pink",
              "trait_key": "favourite-colour"
            }
          ]
        }
    """.trimIndent()
}
