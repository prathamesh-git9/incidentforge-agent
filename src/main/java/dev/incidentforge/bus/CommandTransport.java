package dev.incidentforge.bus;

/** Delivers a serialized run command. Must throw if delivery is not confirmed, so the outbox retries. */
public interface CommandTransport {

    void send(String runId, String payload);
}
