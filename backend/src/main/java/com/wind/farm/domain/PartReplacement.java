package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 备件更换登记：更换数量 > 0 且不得超过当前库存；出库后库存低于安全库存时打预警标记。
 */
@Entity
@Table(name = "part_replacement")
@Getter
@Setter
public class PartReplacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    /** 备件编号（冗余） */
    @Column(name = "part_code", length = 32)
    private String partCode;

    /** 备件名称（冗余） */
    @Column(name = "part_name", length = 64)
    private String partName;

    /** 更换到哪个机位 */
    @Column(name = "turbine_id")
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    /** 关联检修工单 id */
    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    /** 更换数量，必须 > 0 且不得超过当前库存 */
    @Column(name = "qty", nullable = false)
    private Integer qty;

    /** 更换时间 */
    @Column(name = "replace_time")
    private LocalDateTime replaceTime;

    /** 更换人 */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 幂等键：网络重试 / 并发重复提交同一登记时只留一条有效履历；旧数据为 NULL（唯一索引不约束 NULL） */
    @Column(name = "request_id", length = 64, unique = true)
    private String requestId;

    /** 预警标记：出库后库存低于安全库存时为 true */
    @Column(name = "warning_flag")
    private Boolean warningFlag;

    /** 出库后剩余库存 */
    @Column(name = "stock_after")
    private Integer stockAfter;

    @Column(name = "note", length = 128)
    private String note;
}
