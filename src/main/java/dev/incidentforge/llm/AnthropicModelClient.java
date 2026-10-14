package dev.incidentforge.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import dev.incidentforge.config.IncidentForgeProperties;
import java.util.stream.Collectors;

/**
 * Claude adapter built on the official Anthropic Java SDK. Model id, effort and token budget
 * come from the per-stage budget in configuration. The SDK's own retry (429/5xx/network) is
 * bounded by {@code max-retries}; anything still failing is classified for the pipeline's retry
 * policy. Requests and responses are never logged by this class.
 */
public class AnthropicModelClient implements ModelClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final boolean refusalFallback;

    public AnthropicModelClient(IncidentForgeProperties.Llm.Anthropic config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("incidentforge.llm.provider=anthropic requires ANTHROPIC_API_KEY");
        }
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .timeout(config.timeout())
                .maxRetries(config.maxRetries())
                .build();
        this.refusalFallback = config.refusalFallback();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public Reply complete(Request request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxOutputTokens())
                .system(request.system())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort())).build());
        for (Turn turn : request.turns()) {
            if (turn.role() == Role.USER) {
                params.addUserMessage(turn.text());
            } else if (turn.providerPayload() instanceof Message message) {
                // Replay the assistant turn exactly as returned (including thinking blocks).
                params.addMessage(message);
            } else {
                params.addAssistantMessage(turn.text());
            }
        }
        if (refusalFallback) {
            // Server-side fallback on safety-classifier refusals (opt-in beta; disable with refusal-fallback=false).
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        Message message;
        try {
            message = client.messages().create(params.build());
        } catch (RateLimitException | InternalServerException e) {
            throw new TransientModelException("Claude API unavailable (status " + e.statusCode() + ")", e);
        } catch (AnthropicIoException e) {
            throw new TransientModelException("Claude API network failure", e);
        } catch (AnthropicServiceException e) {
            throw new ModelCallException("Claude API rejected the request (status " + e.statusCode() + ")", e);
        }
        String stopReason = message.stopReason().map(StopReason::toString).orElse("unknown");
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new ModelCallException("Claude declined the request (stop_reason=refusal)", null);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"));
        return new Reply(text, message.usage().inputTokens(), message.usage().outputTokens(), stopReason, message);
    }
}
