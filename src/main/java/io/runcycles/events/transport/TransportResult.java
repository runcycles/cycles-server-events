package io.runcycles.events.transport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportResult {

    private boolean success;
    private int statusCode;
    private int latencyMs;
    private String errorMessage;
}
