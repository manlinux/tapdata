package com.tapdata.tm.webhook.vo;

import lombok.Data;

@Data
public class WebHookHistoryInfoVo {
    String id;
    String hookId;
    String url;
    String eventType;
    String status;

    String requestId;
    String requestHeard;
    String requestBody;
    String requestParams;
    Long requestAt;

    String responseHeard;
    String responseResult;
    String responseStatus;
    Integer responseCode;
    Long responseAt;

    /**
     * @see com.tapdata.tm.webhook.enums.WebHookHistoryStatus
     */
    String historyStatus;
    String historyMessage;
}
