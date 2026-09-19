package ai.deepseek.dshphone.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 图片块协议 ——————————————————————————————————————————————————————
//
// 逐条对齐 Web 端的解析口径：
//   packages/client/ui-tool/src/client/tool/models/image-card-model.js
//     （IMAGE_MEDIA_TYPES / positiveInteger / imageReferences / imageTexts /
//       fullyRendered / imageCardModel）
//   packages/client/ui-chat/src/client/chat/MessageItem.js contentParts()
//
// 线上形状：
//   {"type":"image","attachment":{
//      "attachmentId":"sha256:...", "mediaType":"image/png",
//      "bytes":56549, "width":1080, "height":2400,
//      "name":"emu1.png", "originalDimensions":{"width":..,"height":..}}}
//
// 字节不走消息体：图片由 host 落盘，客户端拿 attachmentId 去
// session/attachment 换 base64（Android 侧见 PhoneModel.attachmentImage）。

/** 一张持久化图片块可以声明的媒体类型；其余一律拒绝（web 同名常量）。 */
val IMAGE_MEDIA_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * read_image 写给模型的那封信封（web 的 IMAGE_ENVELOPE，按形状匹配）。
 *
 * 它是图片卡片的识别闸门：结果里没有这个形状的文本块，就不是一次规范的图片读取。
 */
private val IMAGE_ENVELOPE =
    Regex("^<path>[^\\n]*</path>\\n<type>image</type>\\n<content>\\n[\\s\\S]*\\n</content>$")

/** web positiveInteger()：必须是正整数的 JSON 数字（字符串数字不算）。 */
private fun positiveInteger(value: JsonElement?): Int? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    if (number <= 0 || number != Math.floor(number) || number.isInfinite()) return null
    return number.toInt()
}

private fun jsonString(value: JsonElement?): String? {
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

/**
 * 宽松解码一份 attachment：只要 attachmentId 是非空字符串就给引用。
 *
 * 用户/助手气泡走这条（web contentParts 也不做字段校验）；要严格校验的是
 * read_image 卡片，见 [imageReferences]。
 */
fun decodeImageRef(attachment: JsonElement?): ImageAttachmentRef? {
    val obj = attachment as? JsonObject ?: return null
    val attachmentId = jsonString(obj["attachmentId"]) ?: return null
    if (attachmentId.isEmpty()) return null
    val mediaType = jsonString(obj["mediaType"]) ?: "image/png"
    val original = obj["originalDimensions"] as? JsonObject
    return ImageAttachmentRef(
        attachmentId = attachmentId,
        mediaType = mediaType,
        bytes = positiveInteger(obj["bytes"]) ?: 0,
        width = positiveInteger(obj["width"]) ?: 0,
        height = positiveInteger(obj["height"]) ?: 0,
        name = jsonString(obj["name"]),
        originalDimensions = original?.let { dims ->
            val width = positiveInteger(dims["width"]) ?: return@let null
            val height = positiveInteger(dims["height"]) ?: return@let null
            ImageDimensions(width, height)
        },
    )
}

/**
 * web imageReferences()：按顺序收紧结果里的每个 image 块。
 *
 * 内容块是引用的唯一真相来源，且每个字段都是不可信的线上值——任一处不合法就整体
 * 回落（返回 null），而不是画一半的相册。
 */
fun imageReferences(content: List<ContentBlock>): List<ImageAttachmentRef>? {
    val refs = mutableListOf<ImageAttachmentRef>()
    for (block in content) {
        if (block !is ContentBlock.Image) continue
        val attachment = block.attachment as? JsonObject ?: return null
        val attachmentId = jsonString(attachment["attachmentId"]) ?: return null
        if (attachmentId.isEmpty()) return null
        val mediaType = jsonString(attachment["mediaType"]) ?: return null
        if (mediaType !in IMAGE_MEDIA_TYPES) return null
        val bytes = positiveInteger(attachment["bytes"]) ?: return null
        val width = positiveInteger(attachment["width"]) ?: return null
        val height = positiveInteger(attachment["height"]) ?: return null
        val nameElement = attachment["name"]
        val name = if (nameElement == null || nameElement is JsonNull) null else jsonString(nameElement) ?: return null
        val original = attachment["originalDimensions"]
        val dimensions = if (original == null || original is JsonNull) null else {
            val dims = original as? JsonObject ?: return null
            val inputWidth = positiveInteger(dims["width"]) ?: return null
            val inputHeight = positiveInteger(dims["height"]) ?: return null
            ImageDimensions(inputWidth, inputHeight)
        }
        refs.add(
            ImageAttachmentRef(
                attachmentId = attachmentId,
                mediaType = mediaType,
                bytes = bytes,
                width = width,
                height = height,
                name = name,
                originalDimensions = dimensions,
            )
        )
    }
    return refs.ifEmpty { null }
}

/** 不校验、只取能解的图片引用；用于通用工具结果里的图片回退。 */
fun lenientImageRefs(content: List<ContentBlock>): List<ImageAttachmentRef> =
    content.filterIsInstance<ContentBlock.Image>().mapNotNull { decodeImageRef(it.attachment) }

/**
 * web imageTexts()：拼起结果里所有文本块，并要求其中至少有一个是信封形状。
 *
 * 信封是闸门；后置钩子追加的文本块仍然原样留在相册下面。
 */
fun imageTexts(content: List<ContentBlock>): String? {
    val parts = mutableListOf<String>()
    var sawEnvelope = false
    for (block in content) {
        if (block !is ContentBlock.Text) continue
        if (IMAGE_ENVELOPE.matches(block.text)) sawEnvelope = true
        parts.add(block.text)
    }
    return if (sawEnvelope && parts.isNotEmpty()) parts.joinToString("\n") else null
}

/** web fullyRendered()：结果只含本卡片消费得了的文本/图片块，否则回落通用卡。 */
fun fullyRendered(content: List<ContentBlock>): Boolean =
    content.all { it is ContentBlock.Text || it is ContentBlock.Image }

/** 一张 read_image 图片卡：路径标签、持久化图片引用、模型看到的那段文本。 */
data class ImageCard(
    val path: String,
    val images: List<ImageAttachmentRef>,
    val text: String,
)

/** 从工具参数里读一个字符串字段；参数不是合法 JSON 就当作没有。 */
private fun callArgString(argsRaw: String, key: String): String? {
    if (argsRaw.isBlank()) return null
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argsRaw) }.getOrNull()
    val obj = parsed as? JsonObject ?: return null
    return jsonString(obj[key])
}

/**
 * web imageCardModel()：把一次已结算的 read_image 调用收成图片卡，不合格返回 null。
 *
 * 卡片只看结果侧：调用在 execute 返回前没有内容，所以运行中的 read_image 没有卡片。
 * 根调用与嵌套调用都以 ToolResultNode 结算；嵌套的那次（run_code 里派发的 read_image）
 * 不落 presentationMeta，于是标签退回调用自己的 file_path 参数。
 */
fun readImageCard(call: ToolChatData): ImageCard? {
    val result = call.result ?: return null
    if (result.isError) return null
    if (call.name != "read_image") return null
    val filePath = callArgString(call.argsRaw, "file_path") ?: return null
    if (filePath.isBlank()) return null
    val metaPath = (result.meta as? JsonObject)?.let { jsonString(it["path"]) }?.takeIf { it.isNotEmpty() }
    val path = metaPath ?: if (call.parentCallId != null) filePath else null
    if (path == null) return null
    if (!fullyRendered(result.content)) return null
    val refs = imageReferences(result.content) ?: return null
    val text = imageTexts(result.content) ?: return null
    return ImageCard(path = path, images = refs, text = text)
}
