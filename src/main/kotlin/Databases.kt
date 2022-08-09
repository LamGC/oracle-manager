@file:Suppress("SqlNoDataSourceInspection")

package net.lamgc.scext.oraclemanager

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.*
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

val database = DatabaseManager.doInitialDatabase(File("${dataDirectory.canonicalPath}/db/oracle.db"))

val <T : BaseAbilityBot> T.database: Database
    get() = DatabaseManager.getDatabaseByBot(this)

/**
 * 原本是想弄一个机器人一个数据库的。
 * 想了想，太麻烦了。
 */
object DatabaseManager {
    private val databaseMapping = hashMapOf<BaseAbilityBot, Database>()

    @Synchronized
    fun initialDatabase(bot: BaseAbilityBot, dataFolder: File) {
        if (databaseMapping.containsKey(bot)) {
            return
        }
        val database = doInitialDatabase(
            File(
                dataFolder,
                "${bot.botToken.substringBefore(":")}/oracle.db"
            )
        )
        databaseMapping[bot] = database
    }

    fun getDatabaseByBot(bot: BaseAbilityBot): Database {
        return databaseMapping[bot]
            ?: throw IllegalStateException("The database has not been initialized. (bot: `${bot.botUsername}`)")
    }

    fun doInitialDatabase(dbFile: File): Database {
        return Database.connect(generateSqlInUpperCase = true) {
            val dbFolder = dbFile.parentFile
            if (!dbFolder.exists()) {
                dbFolder.mkdirs()
            }

            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.canonicalPath}")
            connection.prepareStatement(
                """
            CREATE TABLE IF NOT EXISTS ${OracleAccountTable.tableName} (
                user_id VARCHAR(128) PRIMARY KEY NOT NULL,
                tenancy_id VARCHAR(128) NOT NULL ,
                region_id VARCHAR(64) NOT NULL ,
                key_fingerprint CHAR(32) NOT NULL ,
                tg_user_id BIGINT NOT NULL,
                name VARCHAR(64) NOT NULL
            );
        """.trimIndent()
            ).execute()
            connection.prepareStatement(
                """
            CREATE TABLE IF NOT EXISTS ${AccessKeyPairTable.tableName} (
                key_fingerprint CHAR(32) PRIMARY KEY NOT NULL,
                private_key TEXT NOT NULL
            )
        """.trimIndent()
            ).execute()
            connection
        }
    }

}

class CharSqlType(private val maxLength: Int) : SqlType<String>(Types.CHAR, "char") {
    override fun doGetResult(rs: ResultSet, index: Int): String? {
        return rs.getString(index)
    }

    override fun doSetParameter(ps: PreparedStatement, index: Int, parameter: String) {
        if (parameter.length > maxLength) {
            throw IllegalArgumentException(
                "The passed in parameter exceeds the maximum allowed value. " +
                        "(expect: $maxLength, actual: ${parameter.length})"
            )
        }
        ps.setString(index, parameter)
    }
}

fun Table<*>.char(name: String, maxLength: Int): Column<String> {
    return registerColumn(name, CharSqlType(maxLength))
}

@Suppress("unused")
object OracleAccountTable : Table<OracleAccountProfilePO>("oracle_accounts") {
    val userId = varchar("user_id").primaryKey().bindTo { it.userId }
    val tenantId = varchar("tenancy_id").bindTo { it.tenantId }
    val regionId = varchar("region_id").bindTo { it.regionId }
    val keyFingerprint = char("key_fingerprint", 32).bindTo { it.keyFingerprint }
    val tgUserId = long("tg_user_id").bindTo { it.telegramUserId }
    val name = varchar("name").bindTo { it.name }
}

val Database.OracleAccounts get() = this.sequenceOf(OracleAccountTable)

@Suppress("unused")
object AccessKeyPairTable : Table<OracleAccessKeyPO>("access_keys") {
    val keyFingerprint = char("key_fingerprint", 32).primaryKey().bindTo { it.fingerprint }
    val privateKey = text("private_key").bindTo { it.privateKeyContent }
}

val Database.OracleAccessKeys get() = this.sequenceOf(AccessKeyPairTable)

interface OracleAccountProfilePO : Entity<OracleAccountProfilePO> {
    var userId: String
    var name: String
    var tenantId: String
    var regionId: String
    var keyFingerprint: String
    var telegramUserId: Long

    companion object : Entity.Factory<OracleAccountProfilePO>() {
        @JvmStatic
        fun fromDataClass(dataClass: OracleAccountProfile): OracleAccountProfilePO {
            return OracleAccountProfilePO {
                userId = dataClass.userId
                name = dataClass.name
                tenantId = dataClass.tenantId
                regionId = dataClass.regionId
                keyFingerprint = dataClass.keyFingerprint.replace(":", "")
                telegramUserId = dataClass.telegramUserId
            }
        }
    }
}

fun <T : OracleAccountProfilePO> T.toDataClass(): OracleAccountProfile {
    return OracleAccountProfile(userId, tenantId, regionId, keyFingerprint, telegramUserId, name)
}

interface OracleAccessKeyPO : Entity<OracleAccessKeyPO> {

    var fingerprint: String
    var privateKeyContent: String

    companion object : Entity.Factory<OracleAccessKeyPO>() {
        fun fromDataClass(dataClass: OracleAccessKey): OracleAccessKeyPO {
            return OracleAccessKeyPO {
                fingerprint = dataClass.fingerprint
                privateKeyContent = dataClass.privateKeyContent
            }
        }
    }
}

fun <T : OracleAccessKeyPO> T.toDataClass(): OracleAccessKey {
    return OracleAccessKey(
        fingerprint = fingerprint,
        privateKeyContent = privateKeyContent
    )
}
