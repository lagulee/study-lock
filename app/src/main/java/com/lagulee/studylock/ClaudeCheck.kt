package com.lagulee.studylock

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude가 "할 일"과 인증 사진을 비교해 실제로 했는지 판단한다.
 * 네트워크를 쓰므로 반드시 화면(메인) 스레드가 아닌 곳에서 부른다.
 */
object ClaudeCheck {
    const val MODEL_SMART = "claude-opus-5-5"
    const val MODEL_LIGHT = "claude-haiku-5-5"

    private val schema: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty(
            "properties", JsonValue.from(
                mapOf(
                    "seen" to mapOf("type" to "string", "description" to "사진마다 실제로 보이는 것(과목·쪽수·풀이 양·채점 표시)"),
                    "pass" to mapOf("type" to "boolean", "description" to "할 일을 끝낸 증거로 인정하면 true"),
                    "reason" to mapOf("type" to "string", "description" to "학생에게 보여 줄 한국어 1~2문장"),
                )
            )
        )
        .putAdditionalProperty("required", JsonValue.from(listOf("seen", "pass", "reason")))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .build()

    fun check(apiKey: String, lightModel: Boolean, task: String, jpegs: List<String>): AiCheck.Outcome {
        val model = if (lightModel) MODEL_LIGHT else MODEL_SMART
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(90))
            .maxRetries(2)
            .build()
        return try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(AiCheck.SYSTEM)
                .outputConfig(BetaOutputConfig.builder().format(BetaJsonOutputFormat.builder().schema(schema).build()).build())
                .addUserMessageOfContentBlockParams(
                    jpegs.map { b64 ->
                        BetaContentBlockParam.ofImage(
                            BetaBase64ImageSource.builder()
                                .data(b64)
                                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                .build()
                        )
                    } + BetaContentBlockParam.ofText(AiCheck.userText(task, jpegs.size))
                )
            if (!lightModel) {
                // 안전 분류기가 거절하면 서버가 다른 모델로 자동으로 다시 시도한다
                builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
            }
            val msg = client.beta().messages().create(builder.build())
            if (msg.stopReason().map { it == BetaStopReason.REFUSAL }.orElse(false)) {
                return AiCheck.Outcome.Failed("Claude가 이 사진은 판단하지 않겠다고 했어요. 다른 각도로 다시 찍어 주세요.")
            }
            val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
            AiCheck.parse(text)
        } catch (e: UnauthorizedException) {
            AiCheck.Outcome.Failed("API 키가 맞지 않아요. 설정에서 키를 다시 확인해 주세요.")
        } catch (e: PermissionDeniedException) {
            AiCheck.Outcome.Failed("이 API 키로는 이 모델을 쓸 수 없어요. 설정에서 다른 모델을 골라 보세요.")
        } catch (e: RateLimitException) {
            AiCheck.Outcome.Failed("요청이 너무 많아요. 잠시 뒤에 다시 시도해 주세요.")
        } catch (e: BadRequestException) {
            if (e.message.orEmpty().contains("credit", ignoreCase = true)) {
                AiCheck.Outcome.Failed("API 충전 잔액이 부족해요. 부모님께 충전을 부탁해 주세요.")
            } else {
                AiCheck.Outcome.Failed("요청에 문제가 있어요: ${e.message}")
            }
        } catch (e: AnthropicServiceException) {
            AiCheck.Outcome.Failed("Claude 서버 오류예요(${e.statusCode()}). 잠시 뒤에 다시 시도해 주세요.")
        } catch (e: AnthropicIoException) {
            AiCheck.Outcome.Failed("인터넷에 연결할 수 없어요. 와이파이나 데이터를 확인해 주세요.")
        } catch (e: Exception) {
            AiCheck.Outcome.Failed("확인 중 오류가 났어요: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            client.close()
        }
    }
}
