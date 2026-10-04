package com.wind.farm.dto;

import lombok.Data;

/**
 * 备件更换登记请求。
 *
 * <p>机位 / 工单关联规则（服务端权威，前端仅做提示）：
 * <ul>
 *   <li>两者都传：工单所属机位必须与所选机位一致，冲突一律拒绝；</li>
 *   <li>只传工单：由工单补齐机位（取舍：减少重复选择，工单是机位关联的权威来源）；</li>
 *   <li>只传机位 / 都不传：按旧行为登记，兼容老客户端。</li>
 * </ul>
 */
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

    /**
     * 幂等键：新客户端每次登记生成一个，网络重试 / 重复点击沿用同一个，
     * 服务端凭它保证同一次更换只留一条有效履历；旧客户端不带则不参与去重（兼容规则）。
     */
    private String requestId;
}
