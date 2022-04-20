package net.lamgc.scext.oraclemanager

import com.oracle.bmc.ConfigFileReader
import com.oracle.bmc.identity.IdentityClient
import com.oracle.bmc.model.BmcException
import mu.KotlinLogging
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpHost
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.objects.*
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.ByteArrayInputStream
import java.security.interfaces.RSAPrivateCrtKey

private val httpClient = HttpClients.custom()
    .setProxy(HttpHost("127.0.0.1", 1080))
    .build()

@Suppress("unused")
class OracleAccountManagerExtension(private val bot: BaseAbilityBot) : AbilityExtension {

    /**
     * 添加 Oracle 租户并关联 Telegram 账号.
     */
    fun addAccount(): Ability = Ability.builder()
        .name("oc_account_add")
        .info("关联新的 Oracle 账号")
        .locality(Locality.USER)
        .privacy(Privacy.PUBLIC)
        .enableStats()
        .action {
            it.bot().silent().send("发送 API 配置文件，或复制配置文件内容直接发送。", it.chatId())
        }
        .reply(ReplyFlow.builder(bot.db())
            .action { bot, upd ->
                val configFile = if (upd.message.hasDocument()) {
                    val configUrl = bot.getFileUrl(upd.message.document.fileId)
                    val httpResponse = httpClient.execute(HttpGet(configUrl))
                    ConfigFileReader.parse(httpResponse.entity.content, "DEFAULT")
                } else if (upd.message.hasText()) {
                    ConfigFileReader.parse(ByteArrayInputStream(upd.message.text.toByteArray()), "DEFAULT")
                } else {
                    bot.silent().send("上传的配置不包括 DEFAULT 节。", upd.message.chatId)
                    return@action
                }
                if (!configFile.validate()) {
                    bot.silent().send("配置文件无效，请重新发送。", upd.message.chatId)
                    return@action
                }


                val profile = configFile.toOracleAccountProfile(upd.message.from.id)
                val existProfile = OracleAccountManage.getOracleAccountByOracleUserId(profile.userId)
                if (existProfile != null) {
                    bot.silent().send(
                        "Oracle 账号已经被${
                            if (existProfile.telegramUserId == upd.message.chatId) "你" else "其他人"
                        }绑定，请重新上传其他账号的 Oracle。", upd.message.chatId
                    )
                    return@action
                }

                if (!OracleAccessKeyManager.accessKeyContains(profile.keyFingerprint)) {
                    bot.db().getVar<String>("oc_account_add::${upd.message.chatId}::profile").set(
                        profile.toJson()
                    )
                    bot.silent().send(
                        "OK，配置文件检查通过，现在需要发送相应的私钥（机器人的所有人将对密钥的安全性负责），" +
                                "所需密钥的指纹是：\n${configFile["fingerprint"]}", upd.message.chatId
                    )
                } else {
                    OracleAccountManage.addOracleAccount(profile)
                    bot.silent().execute(
                        createSuccessfulMsgWithChangeName(
                            "密钥已存在，Oracle 账号绑定成功！\n" +
                                    "账号名称：${profile.name}",
                            upd.message.chatId, profile
                        )
                    )
                }
            }.onlyIf {
                it.hasMessage() && (
                        it.message.hasDocument() &&
                                it.message.document.fileName.endsWith(".ini", ignoreCase = true)
                                ||
                                it.message.hasText() && it.message.text.trim().startsWith(
                            "[DEFAULT]"
                        ))
            }
            .enableStats(getStatsName("oc_account_add", "add_profile"))
            .next(
                ReplyFlow.builder(bot.db())
                    .action { bot, upd ->
                        try {
                            val privateKey = try {
                                val keyUrl = bot.getFileUrl(upd.message.document.fileId)
                                val response = httpClient.execute(HttpGet(keyUrl))
                                loadPkcs8PrivateKeyFromStream(response.entity.content)
                            } catch (e: Exception) {
                                logger.error(e) { "接收密钥文件时发生错误." }
                                bot.silent().send("接收密钥时发生错误，请重试一次。", upd.message.chatId)
                                return@action
                            }
                            if (privateKey !is RSAPrivateCrtKey) {
                                logger.warn { "用户上传的密钥不符合要求." }
                                bot.silent().send("密钥不符合要求，请重新生成密钥后重新添加 Oracle 账号。", upd.message.chatId)
                                return@action
                            }

                            val profileEntry = bot.db().getVar<String>("oc_account_add::${upd.message.chatId}::profile")
                            val profile = OracleAccountProfile.fromJson(profileEntry.get())
                            val uploadedKeyFingerprint = try {
                                getKeyPairFingerprint(privateKey)
                            } catch (e: Exception) {
                                logger.error(e) { "计算密钥指纹时发生错误." }
                                bot.silent().send("密钥指纹计算失败，请确保上传了正确的密钥。", upd.message.chatId)
                                return@action
                            }
                            if (!profile.keyFingerprint.contentEquals(uploadedKeyFingerprint, ignoreCase = true)) {
                                bot.silent().send("上传的私钥与认证配置的密钥指纹不符。", upd.message.chatId)
                                return@action
                            }

                            OracleAccessKeyManager.addAccessKey(privateKey)
                            OracleAccountManage.addOracleAccount(profile)
                            bot.silent().execute(
                                createSuccessfulMsgWithChangeName(
                                    "密钥已确定，Oracle 账号绑定成功！\n" +
                                            "账号名称：${profile.name}", upd.message.chatId, profile
                                )
                            )
                            profileEntry.set(null)
                        } catch (e: Exception) {
                            logger.error(e) { "处理密钥时发生错误." }
                            bot.silent().send("处理密钥时出现未知错误！请联系机器人管理员。", upd.message.chatId)
                        }
                    }
                    .onlyIf {
                        it.hasMessage() && bot.db().getVar<String>("oc_account_add::${it.message.chatId}::profile")
                            .get() != null &&
                                it.message.hasDocument() &&
                                it.message.document.fileName.endsWith(".pem", ignoreCase = true)
                    }
                    .enableStats(getStatsName("oc_account_add", "add_private_key"))
                    .build())
            .build())
        .build()

    fun listOracleAccount(): Ability = Ability.builder()
        .name("oc_account_list")
        .info("列出关联的 Oracle 账号")
        .privacy(Privacy.PUBLIC)
        .locality(Locality.USER)
        .action {
            doListOracleAccount(it.bot(), it.chatId(), it.user().id)
        }
        .build()

    private fun createSuccessfulMsgWithChangeName(
        msg: String,
        chatId: Long,
        profile: OracleAccountProfile,
        changeNameMsg: String = "点此更改机器人的名称"
    ): SendMessage {
        val markup = InlineKeyboardGroupBuilder()
            .configure {
            }
            .newRow()
            .addButton {
                text(changeNameMsg)
                callbackData(action = "oc_account_change_name", profile.toJson())
            }
            .build()
        return SendMessage.builder()
            .chatId(chatId.toString())
            .text(msg)
            .replyMarkup(markup)
            .build()
    }

    fun listOracleAccountReply(): Reply = Reply.of({ bot, upd ->
        doListOracleAccount(
            bot,
            upd.callbackQuery.message.chatId,
            upd.callbackQuery.from.id,
            upd.callbackQuery.message.messageId
        )
    }, callbackQueryAt("oc_account_list"))

    private fun doListOracleAccount(bot: BaseAbilityBot, chatId: Long, userId: Long, messageId: Int? = null) {
        val accounts = OracleAccountManage.getOracleAccountsByTelegramUserId(userId)
        if (accounts.isEmpty()) {
            bot.silent().send("你还没有绑定任何 Oracle 账号，请使用【/oc_account_add】绑定一个 Oracle 账号。", chatId)
            return
        }
        // TODO: 要弄个页面, 防止账号太多刷爆了
        val keyboardGroup = InlineKeyboardMarkup.builder()
        for (account in accounts) {
            val provider = account.getAuthenticationDetailsProvider()
            val identityClient = IdentityClient(provider)
            val text = try {
                val user = identityClient.getUser(provider.userId)
                "${account.name} / ${user.name}（${user.email}）【${account.regionId}】"
            } catch (e: BmcException) {
                "${account.name} / null 【${account.regionId}】"
            }
            keyboardGroup.keyboardRow(
                listOf(
                    InlineKeyboardButton.builder()
                        .text(text)
                        .callbackData(
                            action = "oc_account_manager",
                            extraData = account.toJson()
                        )
                        .build()
                )
            )
        }
        val text = """
                当前 Telegram 用户已绑定以下 Oracle 账号
                （账号没有名字只有邮箱是因为通过 API 获取名字失败）
                """.trimIndent()

        if (messageId == null) {
            SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboardGroup.build())
                .build().execute(bot.silent())
        } else {
            EditMessageText.builder()
                .text(text)
                .messageId(messageId)
                .chatId(chatId.toString())
                .replyMarkup(keyboardGroup.build())
                .build().execute(bot.silent())
        }
    }

    fun manageOracleAccount(): Reply = Reply.of({ bot, upd ->
        val keyboardCallback = upd.callbackQuery.callbackData
        val profile = OracleAccountProfile.fromJson(keyboardCallback.extraData!!)
        val identityClient = IdentityClient(profile.getAuthenticationDetailsProvider())
        val user = try {
            identityClient.getUser(profile.userId)
        } catch (e: Exception) {
            logger.warn(e) { "Oracle 账号信息获取失败. (UserId: ${profile.userId})" }
            null
        }
        val newKeyboardMarkup = InlineKeyboardGroupBuilder()
            .newRow()
            .addButton {
                text("服务器列表")
                callbackData(action = "oc_server_list", keyboardCallback.extraData)
            }
            .newRow()
            .addButton {
                text("账号管理")
                callbackData(action = "oc_account_edit", keyboardCallback.extraData)
            }
            .newRow().addButton {
                text("<<< 返回上一级")
                callbackData(action = "oc_account_list")
            }
            .then().build()

        val editMessageText = EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .text(
                """
                ${profile.name}
                账号名（甲骨文上面的账号名）： ${user?.name}
                区域 Id：${profile.regionId}
                邮箱：${user?.email}
                当前状态：${user?.lifecycleState?.value}
                （账号名、邮箱和当前状态为 null 不一定是因为封号，也可能是服务器网络问题）
                """.trimIndent()
            )
            .replyMarkup(newKeyboardMarkup)
            .build()
        bot.silent().execute(editMessageText)
    }, callbackQueryAt("oc_account_manager"))

    fun editOracleAccount(): Reply = Reply.of({ bot, upd ->
        val keyboardCallback = upd.callbackQuery.callbackData
        val newKeyboardMarkup = InlineKeyboardGroupBuilder()
            .newRow()
            .addButton {
                text("更改名称")
                callbackData(action = "oc_account_change_name", keyboardCallback.extraData)
            }
            .newRow()
            .addButton {
                text("解绑 Oracle 账号")
                callbackData(action = "oc_account_remove", keyboardCallback.extraData)
            }
            .newRow().addButton {
                text("<<< 返回上一级")
                callbackData(action = "oc_account_manager", keyboardCallback.extraData)
            }
            .then().build()

        val editMessageReplyMarkup = EditMessageReplyMarkup.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(newKeyboardMarkup)
            .build()
        bot.silent().execute(editMessageReplyMarkup)
    }, callbackQueryAt("oc_account_edit"))

    fun removeOracleAccount(): Reply = ReplyFlow.builder(bot.db())
        .action { bot, upd ->
            val profile = OracleAccountProfile.fromJson(upd.callbackQuery.callbackData.extraData!!)
            val keyboardCallback = upd.callbackQuery.callbackData
            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text(
                    """
                    ${profile.name} (${profile.regionId})
                    你确定解绑这个 Oracle 账号吗？
                """.trimIndent()
                )
                .replyMarkup(
                    createPromptKeyboard(
                        yesCallback = keyboardCallback.next("oc_account_remove_yes"),
                        noCallback = keyboardCallback.next("oc_account_edit")
                    )
                )
                .build()
                .execute(bot.silent())
        }
        .onlyIf(callbackQueryAt("oc_account_remove"))
        .next(Reply.of({ bot, upd ->
            val keyboardCallback = upd.callbackQuery.callbackData
            val profile = OracleAccountProfile.fromJson(keyboardCallback.extraData!!)
            val result =
                OracleAccountManage.removeOracleAccountByOracleUserId(profile.userId, upd.callbackQuery.from.id)
            val msg = if (result) {
                "Oracle 账号 ${profile.userId} 已成功解除绑定。"
            } else {
                "Oracle 账号 ${profile.userId} 已成功解除绑定。"
            }
            EditMessageText.builder()
                .text(msg)
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .replyMarkup(InlineKeyboardMarkup.builder().clearKeyboard().build())
                .build()
                .execute(bot.silent())
        }, callbackQueryAt("oc_account_remove_yes")))
        .build()

    fun changeOracleAccountName(): Reply = ReplyFlow.builder(bot.db())
        .enableStats(getStatsName("oc_account_change_name", "query_name"))
        .action { bot, upd ->
            if (upd.callbackQuery.data == null || upd.callbackQuery.data.trim().isEmpty()) {
                logger.error { "存在未传递 Profile 的 CallbackQuery 路径，请检查！" }
                bot.silent().send("出现未知错误，请联系机器人管理员。", upd.callbackQuery.message.chatId)
                return@action
            }
            val profile = OracleAccountProfile.fromJson(upd.callbackQuery.callbackData.extraData!!)
            val entryName = "oc_account_change_name::cache::" +
                    "chat_${upd.callbackQuery.message.chatId}::user_${upd.callbackQuery.from.id}::profile"
            logger.debug { "询问名称 - Profile 键名称：$entryName" }

            bot.db().getVar<String>(entryName).set(upd.callbackQuery.callbackData.extraData)
            bot.silent().send(
                "当前机器人的名称为：\n${profile.name}\n请发送机器人的新名称。",
                upd.callbackQuery.message.chatId
            )
        }
        .onlyIf(callbackQueryAt("oc_account_change_name"))
        .next(Reply.of({ bot, upd ->
            val entryName = "oc_account_change_name::cache::" +
                    "chat_${upd.message.chatId}::user_${upd.message.from.id}::profile"

            val profileJson = bot.db().getVar<String>(entryName).get()
            if (profileJson == null || profileJson.trim().isEmpty()) {
                bot.silent().send("会话已过期，请重试一次。", upd.message.chatId)
                return@of
            }

            val profile = OracleAccountProfile.fromJson(profileJson)
            profile.name = upd.message.text.trim()
            try {
                OracleAccountManage.updateOracleAccount(profile)
                bot.silent().send("Oracle 账号名称已更新成功。", upd.message.chatId)
            } catch (e: Exception) {
                logger.error(e) { "更新 Oracle 账号时发生错误." }
                bot.silent().send("更新 Oracle 账号名称时发生错误，请联系机器人管理员。", upd.message.chatId)
            }
        }, { upd -> upd.hasMessage() && upd.message.hasText() }))
        .build()

    fun clearUnusedAccessKey(): Ability = Ability.builder()
        .name("oc_clear_key")
        .info("清除未使用的 API 访问密钥")
        .locality(Locality.USER)
        .privacy(Privacy.ADMIN)
        .action {
            val count = OracleAccessKeyManager.cleanUnusedAccessKey()
            it.bot().silent().send("已清理 $count 个未使用的访问密钥。", it.chatId())
        }
        .build()

    companion object {
        @JvmStatic
        private val logger = KotlinLogging.logger { }
    }
}