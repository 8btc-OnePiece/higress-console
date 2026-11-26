package com.alibaba.higress.sdk.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Enable or disable forwarding while retaining the AI route and its plugins")
public class AiRouteStatusRequest {
    @Schema(description = "Current route version", required = true)
    private String version;
    @Schema(description = "Desired forwarding state", required = true)
    private Boolean enabled;
}
