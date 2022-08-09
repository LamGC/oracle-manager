@file:Suppress("unused")

package net.lamgc.scext.oraclemanager

import com.google.common.base.Strings
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
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
import org.telegram.abilitybots.api.objects.Reply
import org.telegram.abilitybots.api.sender.MessageSender
import org.telegram.abilitybots.api.sender.SilentSender
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import java.util.regex.Pattern

fun BaseAbilityBot.getFileAsStream(fileId: String): InputStream {
    val file = execute(GetFile(fileId))
    return downloadFileAsStream(file)
}

fun BaseAbilityBot.getFileUrl(fileId: String, apiServer: String = "https://api.telegram.org"): String {
    val file = execute(GetFile(fileId))
    return "$apiServer/file/bot${botToken}/${file.filePath}"
}

fun <T : HttpResponse> T.isSuccess(): Boolean = code == 200

fun <T : HttpResponse> T.hasContent(): Boolean = this is HttpEntityContainer

fun <T : AuthenticationDetailsProvider> T.validate(): Boolean {
    return OCID.isValid(userId) &&
            OCID.isValid(tenantId) &&
            !Strings.isNullOrEmpty(fingerprint) &&
            fingerprintCheckPattern.matcher(fingerprint).matches()
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

    fun rowButton(apply: InlineKeyboardButtonBuilder.() -> Unit): InlineKeyboardGroupBuilder {
        return newRow().addButton(apply).then()
    }

    fun build(): InlineKeyboardMarkup = builder.build()
}

@Suppress("MemberVisibilityCanBePrivate")
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
        if (row.isNotEmpty()) {
            groupBuilder.addRow(row)
        }
        return groupBuilder
    }

    fun newRow(): InlineKeyboardRowBuilder {
        return then().newRow()
    }

    fun build(): InlineKeyboardMarkup {
        return then().build()
    }
}

fun InlineKeyboardButtonBuilder.emptyData() {
    callbackData("""{"__EMPTY__": null}""")
}

fun InlineKeyboardGroupBuilder.addBackButton(
    callback: InlineKeyboardCallback,
    message: String = "<<< 返回上一级",
): InlineKeyboardGroupBuilder {
    rowButton {
        text(message)
        callbackData(callback)
    }
    return this
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

fun checkCallbackQueryIsProfileOwner(): (Update) -> Boolean = { upd ->
    upd.hasCallbackQuery() && upd.callbackQuery.callbackDataOrNull != null &&
            upd.callbackQuery.from.id == getProfileByCallback(upd.callbackQuery.callbackData).telegramUserId
}

private val callbackCache: Cache<String, InlineKeyboardCallback> = CacheBuilder.newBuilder()
    .expireAfterAccess(10, TimeUnit.MINUTES)
    .softValues()
    .build()

fun InlineKeyboardButtonBuilder.callbackData(callback: InlineKeyboardCallback): InlineKeyboardButtonBuilder {
    var cacheReferenceCode: String
    do {
        cacheReferenceCode = Random().randomString(32)
    } while (callbackCache.getIfPresent(cacheReferenceCode) != null)
    callbackCache.put(cacheReferenceCode, callback)
    callbackData("{\"rcode\":\"$cacheReferenceCode\"}")
    return this
}

fun InlineKeyboardButtonBuilder.callbackData(
    action: String,
    extraData: JsonObject? = null
): InlineKeyboardButtonBuilder {
    callbackData(InlineKeyboardCallback(action, extraData ?: JsonObject()))
    return this
}

fun createPromptKeyboard(
    yesCallback: InlineKeyboardCallback,
    noCallback: InlineKeyboardCallback,
    yesMsg: String = "确认",
    noMsg: String = "取消"
): InlineKeyboardMarkup {
    return InlineKeyboardGroupBuilder()
        .rowButton {
            text(yesMsg)
            callbackData(yesCallback)
        }
        .rowButton {
            text(noMsg)
            callbackData(noCallback)
        }
        .build()
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>> Method.execute(sender: AbsSender): T {
    return sender.execute(this)
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>> Method.execute(sender: MessageSender): T {
    return sender.execute(this)
}

fun <T : java.io.Serializable, Method : BotApiMethod<T>> Method.execute(sender: SilentSender): Optional<T>? {
    return sender.execute(this)
}

val gson = Gson()

@Suppress("MemberVisibilityCanBePrivate")
data class InlineKeyboardCallback(
    @SerializedName("a") val action: String,
    @SerializedName("d") val extraData: JsonObject = JsonObject()
) {

    fun toJson(): String {
        return gson.toJson(this)
    }

    /**
     * 创建下一动作的回调数据对象.
     * @param newAction 下一个动作名称.
     * @param newExtraData 新的回调额外数据, 如果不为 null, 将会跟原本的 JsonObject 合并, 重复项将使用新 JsonObject 的值.
     * @return 返回新的回调数据对象.
     */
    fun next(
        newAction: String,
        newExtraData: JsonObject? = null,
        replaceData: Boolean = false
    ): InlineKeyboardCallback {
        if (newExtraData != null) {
            if (replaceData) {
                return InlineKeyboardCallback(newAction, newExtraData)
            }
            val nextExtraData = this.extraData.deepCopy()
            for (key in newExtraData.keySet()) {
                val value = newExtraData[key]
                if (value == JsonNull.INSTANCE) {
                    if (nextExtraData.has(key)) {
                        nextExtraData.remove(key)
                    }
                } else {
                    nextExtraData.add(key, value)
                }
            }
            return InlineKeyboardCallback(newAction, nextExtraData)
        }
        return InlineKeyboardCallback(newAction, this.extraData)
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

fun callbackQueryOf(
    action: String,
    checkProfileOwner: Boolean = true,
    block: (BaseAbilityBot, Update) -> Unit,
): Reply {
    return Reply.of(block, mutableListOf<Predicate<Update>?>().apply {
        add(callbackQueryAt(action))
        if (checkProfileOwner) {
            add(checkCallbackQueryIsProfileOwner())
        }
    }.toList())
}

fun callbackQueryHandleOf(
    action: String,
    extraCondition: List<Predicate<Update>> = emptyList(),
    checkProfileOwner: Boolean = true,
    block: CallbackQueryHandler.() -> Unit,
): Reply {
    return Reply.of({ bot, upd ->
        CallbackQueryHandler(bot, upd).block()
    }, mutableListOf<Predicate<Update>?>().apply {
        add(callbackQueryAt(action))
        addAll(extraCondition)
        if (checkProfileOwner) {
            add(checkCallbackQueryIsProfileOwner())
        }
    }.toList())
}

class CallbackQueryHandler(val bot: BaseAbilityBot, val upd: Update) {
    val callbackData: InlineKeyboardCallback
        get() = upd.callbackQuery.callbackData

    val chatId: Long
        get() = upd.callbackQuery.message.chatId

    val user: User
        get() = upd.callbackQuery.from

}

fun getProfileByCallback(callback: InlineKeyboardCallback): OracleAccountProfile {
    return OracleAccountProfile.fromJson(callback.extraData[JsonFields.AccountProfile].asJsonObject)
}

class JsonObjectBuilder(private val jsonObject: JsonObject) {

    operator fun String.plusAssign(json: JsonElement?) {
        jsonObject.add(this, json)
    }

    operator fun String.plusAssign(value: String?) {
        jsonObject.addProperty(this, value)
    }

    operator fun String.plusAssign(value: Boolean?) {
        jsonObject.addProperty(this, value)
    }

    operator fun String.plusAssign(value: Char?) {
        jsonObject.addProperty(this, value)
    }

    operator fun String.plusAssign(value: Number?) {
        jsonObject.addProperty(this, value)
    }

    operator fun String.plusAssign(value: Any?) {
        jsonObject.add(this, if (value == null) JsonNull.INSTANCE else gson.toJsonTree(value))
    }

    fun String.delete(setNull: Boolean = false) {
        if (setNull) {
            jsonObject.add(this, JsonNull.INSTANCE)
        } else {
            jsonObject.remove(this)
        }
    }
}

fun jsonObjectOf(jsonObject: JsonObject = JsonObject(), block: JsonObjectBuilder.() -> Unit): JsonObject {
    JsonObjectBuilder(jsonObject).block()
    return jsonObject
}

object JsonFields {

    /**
     * 子网显示名称.
     * 类型为 [String]
     */
    const val SubnetDisplayName = "subnet_display_name"

    /**
     * 镜像 Id.
     * 类型为 [String]
     */
    const val ImageId = "image_id"

    /**
     * 镜像显示名称.
     * 类型为 [String]
     */
    const val ImageDisplayName = "image_display_name"

    /**
     * 灵活规格的 CPU 数量.
     * 类型为 [Float]
     */
    const val ShapeCpus = "shape_cpus"

    /**
     * 灵活规格的内存数量.
     * 类型为 [Float]
     */
    const val ShapeMemories = "shape_memories"

    /**
     * 实例规格 Id.
     * 类型为 [String]
     */
    const val ShapeId = "shape_id"

    /**
     * 实例规格.
     * 类型为 [JsonObject] -> [Shape]
     */
    const val Shape = "shape"

    /**
     * [OracleAccountProfile] 字段
     * 类型为 [JsonObject]
     */
    const val AccountProfile = "account_profile"

    /**
     * [ServerInstance]
     * 类型为 [JsonObject]
     */
    const val ServerInstance = "server_instance"

    /**
     * VCN 的 [OCID]
     * 类型为 [String]
     */
    const val VcnId = "vcn_id"

    /**
     * VCN 显示名称.
     * 类型为 [String]
     */
    const val VcnDisplayName = "vcn_display_name"

    /**
     * Subnet Id.
     * 类型为 [String]
     */
    const val SubnetId = "subnet_id"

    /**
     * 子网可用域.
     * 类型为 [String]
     */
    const val Subnet_AvailabilityDomain = "subnet_availability_domain"

    /**
     * 子网中的公网 IPv4 Id 列表.
     * 类型为 List<String>
     */
    const val SubnetPublicIps = "subnet_ipv4_public_list"

    /**
     * 公网 IP 的 Id.
     * 类型为 [String]
     */
    const val PublicIpId = "public_ip_id"

    /**
     * 私有 IP 的 OCID
     * 类型为 [String]
     */
    const val PrivateIpId = "private_ip_id"

    /**
     * VCN 信息.
     * 类型为 [JsonObject] => BMC.Vcn
     */
    const val VcnInfo = "vcn_info"

    /**
     * [InstanceAction] 的操作值
     * 类型为 [String]
     */
    const val PowerAction = "power_action"

    /**
     * VNIC ID
     * 类型为 [String]
     */
    const val VnicId = "vnic_id"

    /**
     * 引导卷 Id 列表.
     * 类型为 [Array<String>]
     */
    const val BootVolumeIds = "bootvolume_id_list"

    /**
     * 引导卷 Id.
     * 类型为 [String]
     */
    const val BootVolumeId = "bootvolume_id"

    /**
     * 引导卷名称.
     * 类型为 [String]
     */
    const val BootVolumeDisplayName = "bootvolume_display_name"

    /**
     * 引导卷 Id.
     * 类型为 [com.oracle.bmc.core.model.BootVolume]
     */
    const val BootVolume = "bootvolume"

    /**
     * 实例创建参数.
     * 类型为 [CreateInstanceOptions]
     */
    const val CreateInstanceOptions = "create_instance_options"

    /**
     * 可用域 Id.
     * 类型为 [String]
     */
    const val AvailabilityDomainId = "availability_domain_id"

    /**
     * 可用域名称.
     * 类型为 [String]
     */
    const val AvailabilityDomainName = "availability_domain_name"

    /**
     * 容错域 Id.
     * 类型为 [String]
     */
    const val FaultDomainId = "fault_domain_id"

    /**
     * 容错域名称.
     * 类型为 [String]
     */
    const val FaultDomainName = "fault_domain_name"

    /**
     * 当前页码.
     * 类型为 [Int]
     */
    const val CurrentPageNumber = "current_page_number"

    /**
     * 下一页的页码.
     * 类型为 [String]
     */
    const val NextPageNumber = "next_page_number"

    /**
     * 上一页的页码.
     * 类型为 [String]
     */
    const val PrevPage = "prev_page_number"

    /**
     * 目标页码.
     * 类型为 [String]
     */
    const val JumpToPage = "jump_to_page"

}

fun EditMessageText.EditMessageTextBuilder.replyTo(callbackQuery: CallbackQuery): EditMessageText.EditMessageTextBuilder {
    chatId(callbackQuery.message.chatId.toString())
    messageId(callbackQuery.message.messageId)
    return this
}

fun EditMessageReplyMarkup.EditMessageReplyMarkupBuilder.replyTo(callbackQuery: CallbackQuery)
        : EditMessageReplyMarkup.EditMessageReplyMarkupBuilder {
    chatId(callbackQuery.message.chatId.toString())
    messageId(callbackQuery.message.messageId)
    return this
}

class MapBuilder<K, V>(val map: MutableMap<K, V>) {
    infix fun K.set(value: V?) {
        if (value == null) {
            map.remove(this)
            return
        }
        map[this] = value
    }

    infix fun MapBuilder<K, V>.delete(key: K) {
        map.remove(key)
    }

    infix fun MutableMap<K, V>.delete(key: K) {
        map.remove(key)
    }
}

fun <K, V> mapOf(map: MutableMap<K, V> = mutableMapOf(), block: MapBuilder<K, V>.() -> Unit): Map<K, V> {
    MapBuilder(map).apply(block)
    return map.toMap()
}
