package net.lamgc.scext.oraclemanager

import com.oracle.bmc.identity.IdentityClient
import com.oracle.bmc.identity.model.User
import com.oracle.bmc.identity.requests.GetUserRequest

fun IdentityClient.getUser(userId: String): User {
    return getUser(
        GetUserRequest.builder()
            .userId(userId)
            .build()
    ).user
}