package com.wind.farm.dto;

import lombok.Data;

/** 备件更换登记请求 */
@Data
public class ReplacementRequest {

    /** 备件 id */
    private Long partId;

    /** 更换到哪个机位 */
    private Long turbineId;

    /** 关联检修工单 id */
    private Long workOrderId;

    /** 更换数量，必须 > 0 且不得超过当前库存 */
    private Integer qty;

    /** 更换人 */
    private String operator;

    private String note;
}
