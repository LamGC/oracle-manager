package net.lamgc.scext.oraclemanager

import com.google.common.base.Supplier
import com.oracle.bmc.OCID
import com.oracle.bmc.Region
import com.oracle.bmc.auth.AuthenticationDetailsProvider
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider
import com.oracle.bmc.auth.StringPrivateKeySupplier
import mu.KotlinLogging
import net.lamgc.scalabot.extension.BotExtensionFactory
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.entity.*
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.util.AbilityExtension
import java.io.File
import java.io.InputStream
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey

class ExtensionFactory : BotExtensionFactory {
    override fun createExtensionInstance(bot: BaseAbilityBot, shareDataFolder: File): AbilityExtension {
        dataDirectory = shareDataFolder
        // 初始化一下数据库.
        database
        DatabaseManager.initialDatabase(bot, shareDataFolder)
        return OracleAccountManagerExtension(bot)
    }
}

data class OracleAccountProfile(
    val userId: String,
    val tenantId: String,
    val regionId: String,
    val keyFingerprint: String,
    val telegramUserId: Long,
    var name: String,
) : java.io.Serializable {

    fun toJson(): String = gson.toJson(this)

    fun getAuthenticationDetailsProvider(accessKeyProvider: Supplier<InputStream>? = null): AuthenticationDetailsProvider {
        val accessKey = accessKeyProvider
            ?: (OracleAccessKeyManager.getAccessKeyByFingerprint(keyFingerprint)?.toPrivateKeySupplier()
                ?: throw IllegalStateException("Failed to get accessKey."))
        val builder = SimpleAuthenticationDetailsProvider.builder()
            .userId(userId)
            .tenantId(tenantId)
            .fingerprint(keyFingerprint.chunked(2).joinToString(separator = ":"))
            .region(Region.fromRegionId(regionId))
            .privateKeySupplier(accessKey)
        return builder.build()
    }

    companion object {
        @JvmStatic
        fun fromJson(json: String): OracleAccountProfile =
            gson.fromJson(json, OracleAccountProfile::class.java)
    }
}

/**
 * Oracle API 访问密钥.
 * @property fingerprint 密钥指纹, 算法为 MD5, Base64 编码不带分隔符(所以总长度固定 32 个字符)
 * @property privateKeyContent 密钥的 PEM 格式, 编码协议为 PKCS#8
 */
data class OracleAccessKey(
    val fingerprint: String,
    val privateKeyContent: String
) : java.io.Serializable {
    fun toPrivateKeySupplier(): StringPrivateKeySupplier = StringPrivateKeySupplier(privateKeyContent)
}

/**
 * Oracle API 密钥管理.
 */
object OracleAccessKeyManager {

    private val logger = KotlinLogging.logger { }

    fun addAccessKey(privateKey: PrivateKey): Boolean {
        val fingerprint = getKeyPairFingerprint(privateKey as RSAPrivateCrtKey, separator = "")
        val formatFingerprint = fingerprint.chunked(2).joinToString(separator = ":")
        if (accessKeyContains(fingerprint)) {
            logger.debug { "密钥 $formatFingerprint 已存在, 跳过加载." }
            return true
        }
        return try {
            database.OracleAccessKeys.add(
                OracleAccessKeyPO.fromDataClass(
                    OracleAccessKey(
                        fingerprint,
                        privateKey.toPemString()
                    )
                )
            )
            logger.debug { "密钥 $formatFingerprint 已添加." }
            true
        } catch (e: Exception) {
            logger.error(e) { "添加访问密钥 $formatFingerprint 失败!" }
            false
        }
    }

    fun getAccessKeyByFingerprint(fingerprint: String): OracleAccessKey? {
        return database.OracleAccessKeys.filter { it.keyFingerprint eq fingerprint }.firstOrNull()?.toDataClass()
    }

    fun accessKeyContains(fingerprint: String): Boolean {
        return getAccessKeyByFingerprint(fingerprint) != null
    }

    fun cleanUnusedAccessKey(): Int {
        var count = 0
        database.OracleAccessKeys.forEach { accessKey ->
            val filter = database.OracleAccounts.filter { it.keyFingerprint eq accessKey.fingerprint }.toList()
            if (filter.isEmpty()) {
                database.OracleAccessKeys.removeIf { it.keyFingerprint eq accessKey.fingerprint }
                count++
            }
        }
        return count
    }

}

/**
 * Oracle 账号管理.
 */
object OracleAccountManage {

    fun addOracleAccount(accountProfile: OracleAccountProfile) {
        database.OracleAccounts.add(OracleAccountProfilePO.fromDataClass(accountProfile))
    }

    fun updateOracleAccount(profile: OracleAccountProfile) {
        database.OracleAccounts.update(OracleAccountProfilePO.fromDataClass(profile))
    }

    fun getOracleAccountByOracleUserId(userId: String): OracleAccountProfilePO? {
        if (!OCID.isValid(userId)) {
            throw IllegalArgumentException("Invalid UserId: $userId")
        }
        return database.OracleAccounts.filter { it.userId eq userId }.firstOrNull()
    }

    fun removeOracleAccountByOracleUserId(ocUserId: String, tgUserId: Long): Boolean {
        return database.OracleAccounts.removeIf { it.userId eq ocUserId and (it.tgUserId eq tgUserId) } != 0
    }

    fun getOracleAccountsByTelegramUserId(tgUserId: Long): Set<OracleAccountProfile> {
        return database.OracleAccounts
            .filter { it.tgUserId eq tgUserId }
            .map { it.toDataClass() }
            .toSet()
    }

}
