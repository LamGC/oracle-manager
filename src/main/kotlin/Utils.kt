package net.lamgc.scext.oraclemanager

import com.google.common.base.Strings
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.oracle.bmc.ConfigFileReader.ConfigFile
import com.oracle.bmc.OCID
import com.oracle.bmc.Region
import com.oracle.bmc.auth.AuthenticationDetailsProvider
import org.apache.hc.core5.http.HttpEntityContainer
import org.apache.hc.core5.http.HttpResponse
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.util.io.pem.PemWriter
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.sender.MessageSender
import org.telegram.abilitybots.api.sender.SilentSender
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.InlineKeyboardMarkupBuilder
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.InlineKeyboardButtonBuilder
import org.telegram.telegrambots.meta.bots.AbsSender
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

fun BaseAbilityBot.getFileUrl(fileId: String, apiServer: String = "https://api.telegram.org"): String {
    val file = execute(GetFile(fileId))
    return "$apiServer/file/bot${botToken}/${file.filePath}"
}

fun <T : HttpResponse> T.isSuccess(): Boolean = code == 200

fun <T : HttpResponse> T.hasContent(): Boolean = this is HttpEntityContainer

fun <T : AuthenticationDetailsProvider> T.validate(): Boolean {
    return !Strings.isNullOrEmpty(userId) &&
            !Strings.isNullOrEmpty(tenantId) &&
            !Strings.isNullOrEmpty(fingerprint)
}

private val fingerprintCheckPattern = Pattern.compile("^[\\da-zA-Z]{2}(:[\\da-zA-Z]{2}){15}\$")

fun ConfigFile.validate(requireKeyPath: Boolean = false): Boolean {
    val result = OCID.isValid(this["user"]) &&
            OCID.isValid(this["tenancy"]) &&
            this["fingerprint"] != null &&
            fingerprintCheckPattern.matcher(this["fingerprint"]).matches()
    this["region"] != null &&
            (!requireKeyPath || this["key_file"] != null)
    if (result) {
        try {
            Region.fromRegionId(this["region"])
            return true
        } catch (_: IllegalArgumentException) {
        }
    }
    return false
}

fun ConfigFile.toOracleAccountProfile(telegramUserId: Long, name: String = generateRandomName()): OracleAccountProfile {
    if (!validate()) {
        throw IllegalStateException("Invalid BMC ConfigFile")
    }
    return OracleAccountProfile(
        this["user"],
        this["tenancy"],
        this["region"],
        clearFingerprintSeparator(this["fingerprint"]),
        telegramUserId,
        name
    )
}

private fun clearFingerprintSeparator(source: String): String {
    return source.replace(":", "")
}

fun Boolean.not(action: () -> Unit) {
    if (!this) {
        action()
    }
}

private const val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----"
private const val END_PRIVATE_KEY = "-----END PRIVATE KEY-----"

fun loadPkcs8PrivateKeyFromStream(input: InputStream): PrivateKey {
    val pemContent = input.readAllBytes()
        .toString(StandardCharsets.UTF_8)
        .trim { it == '\n' || it == '\r' || it == ' ' }
    if (!pemContent.startsWith(BEGIN_PRIVATE_KEY) ||
        !pemContent.endsWith(END_PRIVATE_KEY)
    ) {
        throw IllegalArgumentException("Incorrect private key format.")
    }

    val keyString = pemContent
        .replace(BEGIN_PRIVATE_KEY, "")
        .replace(END_PRIVATE_KEY, "")
        .replace("\n", "")
        .replace("\r", "")
    val keyBytes = Base64.getDecoder().decode(keyString)
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
}

fun getKeyPairFingerprint(privateKey: RSAPrivateCrtKey, separator: String = ""): String {
    val keySpec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
    val keyFactory = KeyFactory.getInstance("RSA")
    val publicKey = keyFactory.generatePublic(keySpec)
    val digest = MessageDigest.getInstance("MD5")
    val digestBytes = digest.digest(publicKey.encoded)
    return digestBytes.joinToString(separator = separator) { eachByte -> "%02x".format(eachByte) }
}

/**
 * 将 PrivateKeyInfo 转换成 PrivateKey.
 * @param info 要转换的 PrivateKeyInfo.
 * @return 从 PrivateKeyInfo 转换出来的 PrivateKey 对象.
 */
private fun privateKeyInfoToPrivateKey(info: PrivateKeyInfo): PrivateKey = JcaPEMKeyConverter().getPrivateKey(info)

fun <T : PrivateKey> T.toPemString(): String {
    val writer = StringWriter()
    val pemWriter = PemWriter(writer)
    pemWriter.writeObject(JcaPKCS8Generator(this, null))
    pemWriter.flush()
    return writer.toString()
}

private val internalDataDirectory = AtomicReference<File>()

var dataDirectory: File
    set(value) {
        internalDataDirectory.compareAndSet(null, value)
    }
    get() = internalDataDirectory.get()

class InlineKeyboardGroupBuilder {
    private val builder = InlineKeyboardMarkup.builder()

    fun configure(block: InlineKeyboardMarkupBuilder.() -> Unit): InlineKeyboardGroupBuilder {
        builder.block()
        return this
    }

    fun newRow(): InlineKeyboardRowBuilder {
        return InlineKeyboardRowBuilder(this)
    }

    fun addRow(row: List<InlineKeyboardButton>) {
        builder.keyboardRow(row)
    }

    fun build(): InlineKeyboardMarkup = builder.build()
}

class InlineKeyboardRowBuilder(private val groupBuilder: InlineKeyboardGroupBuilder) {

    private val row = mutableListOf<InlineKeyboardButton>()

    fun addButton(button: InlineKeyboardButton): InlineKeyboardRowBuilder {
        row.add(button)
        return this
    }

    fun addButton(buttonBuilder: InlineKeyboardButtonBuilder): InlineKeyboardRowBuilder {
        addButton(buttonBuilder.build())
        return this
    }

    fun addButton(apply: InlineKeyboardButtonBuilder.() -> Unit): InlineKeyboardRowBuilder {
        val builder = InlineKeyboardButton.builder()
        builder.apply()
        addButton(builder)
        return this
    }

    fun then(): InlineKeyboardGroupBuilder {
        groupBuilder.addRow(row)
        return groupBuilder
    }

    fun newRow(): InlineKeyboardRowBuilder {
        return then().newRow()
    }

    fun build(): InlineKeyboardMarkup {
        return then().build()
    }
}

val CallbackQuery.callbackData: InlineKeyboardCallback
    get() {
        val refJson = gson.fromJson(data, JsonObject::class.java)
        return if (refJson.has("rcode") && refJson.get("rcode").isJsonPrimitive) {
            val refCode = refJson.get("rcode").asString
            callbackCache.getIfPresent(refCode) ?: throw IllegalStateException("CallbackData has expired.")
        } else {
            throw IllegalStateException("RefCode is invalid.")
        }
    }

val CallbackQuery.callbackDataOrNull: InlineKeyboardCallback?
    get() {
        return try {
            callbackData
        } catch (e: Exception) {
            null
        }
    }


fun callbackQueryAt(actionName: String): (Update) -> Boolean {
    return {
        it.hasCallbackQuery() && it.callbackQuery.callbackDataOrNull != null &&
                actionName == it.callbackQuery.callbackData.action
    }
}

private val callbackCache: Cache<String, InlineKeyboardCallback> = CacheBuilder.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build()

fun InlineKeyboardButtonBuilder.callbackData(callback: InlineKeyboardCallback): InlineKeyboardButtonBuilder {
    val cacheReferenceCode = ThreadLocalRandom.current().randomString(32)
    callbackCache.put(cacheReferenceCode, callback)
    callbackData("{\"rcode\":\"$cacheReferenceCode\"}")
    return this
}

fun InlineKeyboardButtonBuilder.callbackData(action: String, extraData: String? = null): InlineKeyboardButtonBuilder {
    callbackData(InlineKeyboardCallback(action, extraData))
    return this
}

fun createPromptKeyboard(
    yesCallback: InlineKeyboardCallback,
    noCallback: InlineKeyboardCallback,
    yesMsg: String = "确认",
    noMsg: String = "取消"
): InlineKeyboardMarkup {
    return InlineKeyboardGroupBuilder()
        .newRow()
        .addButton {
            text(yesMsg)
            callbackData(yesCallback)
        }
        .newRow()
        .addButton {
            text(noMsg)
            callbackData(noCallback)
        }
        .build()
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>, Sender : AbsSender> Method.execute(sender: Sender): T {
    return sender.execute(this)
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>, Sender : MessageSender> Method.execute(sender: Sender): T {
    return sender.execute(this)
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>, Sender : SilentSender> Method.execute(sender: Sender): Optional<T>? {
    return sender.execute(this)
}

val gson = Gson()

@Suppress("MemberVisibilityCanBePrivate")
data class InlineKeyboardCallback(
    @SerializedName("a") val action: String,
    @SerializedName("d") val extraData: String? = null
) {

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun next(newAction: String, newExtraData: String? = null): InlineKeyboardCallback {
        return InlineKeyboardCallback(newAction, newExtraData ?: this.extraData)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: String): InlineKeyboardCallback {
            return gson.fromJson(json, InlineKeyboardCallback::class.java)
        }
    }
}

fun getStatsName(action: String, subActonName: String): String {
    return "$action::action::$subActonName"
}

fun Random.randomString(length: Int): String {
    val builder = StringBuilder()
    for (i in 1..length) {
        val charNumber = nextInt(62)
        val char = when (charNumber) {
            in 0..25 -> (charNumber + 65)
            in 26..51 -> (charNumber % 26 + 97)
            else -> (charNumber - 52 + 48)
        }.toChar()
        builder.append(char)
    }
    return builder.toString()
}
