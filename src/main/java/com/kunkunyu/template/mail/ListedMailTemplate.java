package com.kunkunyu.template.mail;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import java.util.List;


@Data
@Accessors(chain = true)
public class ListedMailTemplate {
    
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private ReasonType reasonType;
    
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NotificationTemplate> templates;
}
