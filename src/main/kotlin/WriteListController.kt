package net.lamgc.scext.oraclemanager

import mu.KotlinLogging
import net.lamgc.scalabot.extension.BotExtensionFactory
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.objects.*
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import java.io.File
import java.util.*
import java.util.function.Predicate

class WriteListController(
    private val bot: BaseAbilityBot,
    region: String = "oci-manager",
    val prefix: String = "oci_",
) {

    private val writeList = bot.db().getMap<Long, String>("$region::write-list")

    /**
     * 检查用户是否在白名单中.
     * @throws IllegalStateException 当用户不在白名单时抛出该异常.
     */
    fun checkUser(userId: Long) {
        if (bot.isCreator(userId)) {
            return
        }
        if (!writeList.containsKey(userId)) {
            throw IllegalStateException("User `$userId` is not in write list.")
        }
    }

    fun addUser(userId: Long, identity: String) {
        writeList[userId] = identity
    }

    fun listUser(): Map<Long, String> {
        return Collections.unmodifiableMap(writeList)
    }

    fun removeUser(userId: Long) {
        writeList.remove(userId)
    }

}

val BaseAbilityBot.writeList: WriteListController get() = WriteListController(this)

fun MessageContext.checkWriteList() {
    bot().writeList.checkUser(user().id)
}

fun checkWriteList(bot: BaseAbilityBot): Predicate<Update> = Predicate {
    try {
        bot.writeList.checkUser(it.message.from.id)
        true
    } catch (e: IllegalStateException) {
        false
    }
}

class WriteListExtensionFactory : BotExtensionFactory {
    override fun createExtensionInstance(bot: BaseAbilityBot, dataFolder: File): AbilityExtension =
        WriteListExtension(bot)
}

@Suppress("unused")
class WriteListExtension(private val bot: BaseAbilityBot) : AbilityExtension {

    private val logger = KotlinLogging.logger { }

    fun listWriteList(): Ability = Ability.builder()
        .privacy(Privacy.CREATOR)
        .locality(Locality.USER)
        .name("${bot.writeList.prefix}wl_list")
        .info("列出已加入白名单的用户。")
        .action {
            val listUser = it.bot().writeList.listUser()
            val strBuilder = StringBuilder("下列用户已加入白名单：\n")
            listUser.forEach { (k, v) ->
                strBuilder.append("($k) $v").append('\n')
            }
            it.bot().silent().send(strBuilder.toString(), it.chatId())
        }
        .build()

    fun addWriteList(): Ability = Ability.builder()
        .privacy(Privacy.CREATOR)
        .locality(Locality.USER)
        .name("${bot.writeList.prefix}wl_add")
        .info("将用户添加到白名单。")
        .action {
            val args = it.arguments()
            if (args.isEmpty()) {
                it.bot().silent().send(
                    "请至少添加一名用户！（用法：`/${bot.writeList.prefix}wl_add @user_name ...`）", it.chatId()
                )
                return@action
            }

            val result = StringBuilder("下列用户已添加白名单：\n")
            args.forEach { arg ->
                val userId = try {
                    arg.toLong()
                } catch (e: NumberFormatException) {
                    return@forEach
                }
                val chat = it.bot().execute(
                    GetChat.builder()
                        .chatId(userId)
                        .build()
                )
                if (!chat.isUserChat) {
                    return@forEach
                }
                it.bot().writeList.addUser(chat.id, chat.userName)
                result.append("${chat.id}  -> @${chat.userName}")
            }
            it.bot().silent().send(result.toString(), it.chatId())
        }
        .build()

    fun removeWriteList(): Ability = Ability.builder()
        .privacy(Privacy.CREATOR)
        .locality(Locality.USER)
        .name("${bot.writeList.prefix}wl_del")
        .info("从白名单中移除用户。")
        .action {
            val args = it.arguments()
            if (args.isEmpty()) {
                it.bot().silent().send(
                    "请至少指定一名用户！（用法：`/${bot.writeList.prefix}wl_add @user_name ...`）", it.chatId()
                )
                return@action
            }

            val result = StringBuilder("下列用户已从白名单中删除：\n")
            args.forEach { arg ->
                val userId = try {
                    arg.toLong()
                } catch (e: NumberFormatException) {
                    return@forEach
                }
                val chat = it.bot().execute(
                    GetChat.builder()
                        .chatId(userId)
                        .build()
                )
                if (!chat.isUserChat) {
                    return@forEach
                }
                it.bot().writeList.removeUser(chat.id)
                result.append("${chat.id}  -> @${chat.userName}")
            }
            it.bot().silent().send(result.toString(), it.chatId())
        }
        .build()

    fun checkWriteList(): Ability = Ability.builder()
        .privacy(Privacy.CREATOR)
        .locality(Locality.USER)
        .name("${bot.writeList.prefix}wl_check")
        .info("检查指定用户是否可以通过白名单。")
        .action {
            val args = it.arguments()
            if (args.isEmpty()) {
                it.bot().silent().send(
                    "请指定一名用户！（用法：`/${bot.writeList.prefix}wl_check @user_name`）", it.chatId()
                )
                return@action
            }

            val userId = try {
                args.first().toLong()
            } catch (e: Exception) {
                it.bot().silent().send("请提供一个用户 Id！", it.chatId())
                return@action
            }
            try {
                it.bot().writeList.checkUser(userId)
                bot.sender().execute(
                    SendMessage.builder()
                        .chatId(it.chatId())
                        .replyToMessageId(it.update().message.messageId)
                        .text("该用户可以通过白名单检查。")
                        .build()
                )
            } catch (e: Exception) {
                bot.sender().execute(
                    SendMessage.builder()
                        .chatId(it.chatId())
                        .replyToMessageId(it.update().message.messageId)
                        .text("该用户无法通过白名单检查。")
                        .build()
                )
            }
        }
        .build()

}
