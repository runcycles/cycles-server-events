package io.runcycles.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Actor {

    /**
     * OPEN STRING on the wire (see {@code Event.category}): actor types are
     * producer-controlled and additive (the admin plane models types this
     * service does not), and the event object is re-serialized as the outbound
     * webhook body — the original value must survive the round trip.
     */
    @JsonProperty("type")
    private String type;

    @JsonProperty("key_id")
    private String keyId;

    @JsonProperty("source_ip")
    private String sourceIp;
}
