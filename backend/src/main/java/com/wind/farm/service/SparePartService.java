package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.PartReplacement;
import com.wind.farm.domain.SparePart;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.ReplacementRequest;
import com.wind.farm.dto.SparePartRequest;
import com.wind.farm.dto.Views.ReplacementVO;
import com.wind.farm.dto.Views.SparePartVO;
import com.wind.farm.repository.PartReplacementRepository;
import com.wind.farm.repository.SparePartRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 备件与更换：备件编号唯一、安全库存不得为负、库存不得为负；
 * 更换数量 > 0 且不得超过当前库存；库存低于安全库存时仍可出库但要打预警标记。
 *
 * <p>更换登记的机位 / 工单关联规则（服务端权威校验，前端只做提示）：
 * <ul>
 *   <li>机位与工单同时传入：工单所属机位必须与所选机位一致，任何冲突一律拒绝；</li>
 *   <li>只传工单：由工单补齐机位（取舍：减少重复选择，工单是机位关联的权威来源）；</li>
 *   <li>只传机位 / 都不传：按旧行为登记，兼容只带其一的旧客户端。</li>
 * </ul>
 *
 * <p>提交时在同一个事务里重新核对：工单所属机位、工单可登记状态（已闭环不可登记）、
 * 备件可用性与本次数量；任一项不通过，整个事务回滚——不扣库存、不生成更换记录、不污染工单履历。
 * 备件行与工单行都用悲观锁读取，两人同时用同一备件也不会把库存扣成负数；
 * request_id 唯一约束 + 幂等回放保证网络重试同一登记只留一条有效履历。
 */
@Service
public class SparePartService {

    private final SparePartRepository partRepository;
    private final PartReplacementRepository replacementRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final WorkOrderRepository orderRepository;
    private final TransactionTemplate txTemplate;

    public SparePartService(SparePartRepository partRepository, PartReplacementRepository replacementRepository,
            TurbineRepository turbineRepository, WindFarmRepository farmRepository,
            WorkOrderRepository orderRepository, PlatformTransactionManager txManager) {
        this.partRepository = partRepository;
        this.replacementRepository = replacementRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.orderRepository = orderRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public List<SparePart> list(String category) {
        if (StringUtils.hasText(category)) {
            return partRepository.findByCategoryOrderByPartCodeAsc(category);
        }
        return partRepository.findAllByOrderByPartCodeAsc();
    }

    public List<SparePartVO> listVO(String category) {
        List<SparePartVO> result = new ArrayList<>();
        for (SparePart part : list(category)) {
            result.add(toVO(part));
        }
        return result;
    }

    public SparePart get(Long id) {
        return partRepository.findById(id).orElseThrow(() -> new BizException("备件不存在：" + id));
    }

    @Transactional
    public SparePart create(SparePartRequest request) {
        if (!StringUtils.hasText(request.getPartCode())) {
            throw new BizException("备件编号不能为空");
        }
        if (partRepository.existsByPartCode(request.getPartCode())) {
            throw new BizException("备件编号已存在：" + request.getPartCode());
        }
        if (request.getCategory() != null && !Dict.isValidPartCategory(request.getCategory())) {
            throw new BizException("备件分类非法：" + request.getCategory());
        }
        if (request.getSafetyStock() != null && request.getSafetyStock() < 0) {
            throw new BizException("安全库存不得为负：" + request.getSafetyStock());
        }
        if (request.getStockQty() != null && request.getStockQty() < 0) {
            throw new BizException("库存不得为负：" + request.getStockQty());
        }
        if (request.getUnitPrice() != null && request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不得为负");
        }
        SparePart part = new SparePart();
        part.setPartCode(request.getPartCode());
        part.setPartName(request.getPartName());
        part.setSpec(request.getSpec());
        part.setCategory(request.getCategory());
        part.setUnit(request.getUnit());
        part.setStockQty(request.getStockQty());
        part.setSafetyStock(request.getSafetyStock());
        part.setLocation(request.getLocation());
        part.setUnitPrice(request.getUnitPrice());
        part.setSupplier(request.getSupplier());
        part.setNote(request.getNote());
        // 默认值写在 create 里，实体字段不带默认值
        if (part.getStockQty() == null) {
            part.setStockQty(0);
        }
        if (part.getSafetyStock() == null) {
            part.setSafetyStock(0);
        }
        if (part.getUnit() == null) {
            part.setUnit("件");
        }
        return partRepository.save(part);
    }

    @Transactional
    public SparePart update(Long id, SparePartRequest request) {
        SparePart part = get(id);
        if (StringUtils.hasText(request.getPartCode())
                && !request.getPartCode().equals(part.getPartCode())
                && partRepository.existsByPartCode(request.getPartCode())) {
            throw new BizException("备件编号已存在：" + request.getPartCode());
        }
        if (request.getCategory() != null && !Dict.isValidPartCategory(request.getCategory())) {
            throw new BizException("备件分类非法：" + request.getCategory());
        }
        if (request.getSafetyStock() != null && request.getSafetyStock() < 0) {
            throw new BizException("安全库存不得为负：" + request.getSafetyStock());
        }
        if (request.getStockQty() != null && request.getStockQty() < 0) {
            throw new BizException("库存不得为负：" + request.getStockQty());
        }
        ifNotNull(request.getPartCode(), part::setPartCode);
        ifNotNull(request.getPartName(), part::setPartName);
        ifNotNull(request.getSpec(), part::setSpec);
        ifNotNull(request.getCategory(), part::setCategory);
        ifNotNull(request.getUnit(), part::setUnit);
        ifNotNull(request.getStockQty(), part::setStockQty);
        ifNotNull(request.getSafetyStock(), part::setSafetyStock);
        ifNotNull(request.getLocation(), part::setLocation);
        ifNotNull(request.getUnitPrice(), part::setUnitPrice);
        ifNotNull(request.getSupplier(), part::setSupplier);
        ifNotNull(request.getNote(), part::setNote);
        return partRepository.save(part);
    }

    /** 步进器改库存：− / + 直接增减当前库存（行锁串行化，并发下不会扣成负数） */
    @Transactional
    public SparePart changeStock(Long id, Integer delta) {
        if (delta == null) {
            throw new BizException("库存变化量不能为空");
        }
        if (delta > Dict.MAX_REPLACE_QTY || delta < -Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次库存调整不得超过 " + Dict.MAX_REPLACE_QTY);
        }
        SparePart part = partRepository.findWithLockById(id)
                .orElseThrow(() -> new BizException("备件不存在：" + id));
        int next = nvl(part.getStockQty()) + delta;
        if (next < 0) {
            throw new BizException("库存不得为负，当前库存 " + nvl(part.getStockQty()) + "，本次调整 " + delta);
        }
        part.setStockQty(next);
        return partRepository.save(part);
    }

    @Transactional
    public void remove(Long id) {
        SparePart part = get(id);
        if (!replacementRepository.findByPartIdOrderByReplaceTimeDesc(id).isEmpty()) {
            throw new BizException("该备件已有更换记录，不允许删除");
        }
        partRepository.delete(part);
    }

    // ---------------- 更换登记 ----------------

    public List<PartReplacement> replacements(Long partId, Long turbineId) {
        if (partId != null) {
            return replacementRepository.findByPartIdOrderByReplaceTimeDesc(partId);
        }
        if (turbineId != null) {
            return replacementRepository.findByTurbineIdOrderByReplaceTimeDesc(turbineId);
        }
        return replacementRepository.findAllByOrderByReplaceTimeDesc();
    }

    public List<ReplacementVO> replacementVOs(Long partId, Long turbineId) {
        List<ReplacementVO> result = new ArrayList<>();
        for (PartReplacement item : replacements(partId, turbineId)) {
            result.add(toReplacementVO(item));
        }
        return result;
    }

    /**
     * 更换登记入口（不在本方法上开事务）：
     * 先做幂等回放，再把「重新核对 + 扣库存 + 写履历」放进一个事务里一次性完成。
     */
    public PartReplacement replace(ReplacementRequest request) {
        String requestId = normalizeRequestId(request.getRequestId());
        if (requestId != null) {
            // 网络重试 / 重复点击：同一 requestId 直接返回已生效的那条，不再扣第二次库存
            PartReplacement existed = replacementRepository.findByRequestId(requestId).orElse(null);
            if (existed != null) {
                return existed;
            }
        }
        try {
            return txTemplate.execute(status -> doReplace(request, requestId));
        } catch (DataIntegrityViolationException e) {
            // 并发下同一 requestId 撞唯一索引：本次事务已整体回滚（不会重复扣库存），已生效的那条就是本次操作的结果
            if (requestId != null) {
                PartReplacement existed = replacementRepository.findByRequestId(requestId).orElse(null);
                if (existed != null) {
                    return existed;
                }
            }
            throw e;
        }
    }

    /**
     * 事务内的完整登记单元：重新核对工单所属机位、工单可登记状态、备件可用性与本次数量，
     * 全部通过才扣减库存并写入更换履历；任一项不通过，抛业务异常整体回滚。
     */
    private PartReplacement doReplace(ReplacementRequest request, String requestId) {
        if (request.getPartId() == null) {
            throw new BizException("请选择要更换的备件");
        }
        if (request.getQty() == null || request.getQty() <= 0) {
            throw new BizException("更换数量必须大于 0");
        }
        if (request.getQty() > Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次更换数量不得超过 " + Dict.MAX_REPLACE_QTY);
        }
        // 行锁读备件：两人同时用同一备件时串行扣减，库存不得为负
        SparePart part = partRepository.findWithLockById(request.getPartId())
                .orElseThrow(() -> new BizException("备件不存在：" + request.getPartId()));

        // 行锁读工单：登记期间工单状态 / 所属机位不会被并发改写
        WorkOrder order = request.getWorkOrderId() == null
                ? null
                : orderRepository.findWithLockById(request.getWorkOrderId())
                        .orElseThrow(() -> new BizException("检修工单不存在：" + request.getWorkOrderId()));
        if (order != null && !Dict.isOrderRegistrable(order.getStatus())) {
            throw new BizException("工单 " + order.getOrderNo() + " 当前状态为「"
                    + Dict.workOrderStatusName(order.getStatus()) + "」，不允许再登记更换；请改选未闭环的工单");
        }

        Turbine turbine = resolveTurbine(request, order);

        int stock = nvl(part.getStockQty());
        if (request.getQty() > stock) {
            throw new BizException("更换数量 " + request.getQty() + " 超过备件 " + part.getPartCode()
                    + " 当前库存 " + stock + " " + part.getUnit() + "；请刷新确认实际剩余量后调整数量");
        }

        int after = stock - request.getQty();
        PartReplacement record = new PartReplacement();
        record.setPartId(part.getId());
        record.setPartCode(part.getPartCode());
        record.setPartName(part.getPartName());
        record.setTurbineId(turbine == null ? null : turbine.getId());
        record.setUnitCode(turbine == null ? null : turbine.getUnitCode());
        record.setWorkOrderId(order == null ? null : order.getId());
        record.setOrderNo(order == null ? null : order.getOrderNo());
        record.setQty(request.getQty());
        record.setReplaceTime(LocalDateTime.now());
        record.setOperator(request.getOperator());
        record.setRequestId(requestId);
        record.setStockAfter(after);
        // 低于安全库存仍可出库，但打预警标记
        boolean warn = after < nvl(part.getSafetyStock());
        record.setWarningFlag(warn);
        record.setNote(warn
                ? "出库后库存 " + after + " 低于安全库存 " + nvl(part.getSafetyStock()) + "，已标记预警"
                : request.getNote());

        part.setStockQty(after);
        partRepository.save(part);
        return replacementRepository.save(record);
    }

    /**
     * 机位 / 工单关联解析（写入链的唯一入口）：
     * 两者都传时任何冲突一律拒绝；只传工单时由工单补齐机位；只传机位时按所选机位登记。
     */
    private Turbine resolveTurbine(ReplacementRequest request, WorkOrder order) {
        Turbine selected = request.getTurbineId() == null
                ? null
                : turbineRepository.findById(request.getTurbineId())
                        .orElseThrow(() -> new BizException("机位不存在：" + request.getTurbineId()));
        if (order == null) {
            return selected;
        }
        if (selected == null) {
            // 只选工单：由工单补齐机位，减少重复选择（工单是机位关联的权威来源）
            return turbineRepository.findById(order.getTurbineId())
                    .orElseThrow(() -> new BizException("工单 " + order.getOrderNo() + " 所属机位不存在，请核对工单数据"));
        }
        if (!selected.getId().equals(order.getTurbineId())) {
            // 同时传入且冲突：拒绝，并指出应修正的机位 / 工单
            String orderUnit = order.getUnitCode() == null ? "id=" + order.getTurbineId() : order.getUnitCode();
            throw new BizException("工单 " + order.getOrderNo() + " 属于机位 " + orderUnit
                    + "，与所选机位 " + selected.getUnitCode() + " 不一致：请把机位修正为 " + orderUnit
                    + "，或改选 " + selected.getUnitCode() + " 名下的工单后重新登记");
        }
        return selected;
    }

    /** 幂等键归一化：空串按未传处理（兼容旧客户端），超长直接拒绝 */
    private static String normalizeRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return null;
        }
        String trimmed = requestId.trim();
        if (trimmed.length() > 64) {
            throw new BizException("requestId 长度不得超过 64 个字符");
        }
        return trimmed;
    }

    // ---------------- VO ----------------

    public SparePartVO toVO(SparePart part) {
        SparePartVO vo = new SparePartVO();
        vo.setId(part.getId());
        vo.setPartCode(part.getPartCode());
        vo.setPartName(part.getPartName());
        vo.setSpec(part.getSpec());
        vo.setCategory(part.getCategory());
        vo.setCategoryName(Dict.partCategoryName(part.getCategory()));
        vo.setUnit(part.getUnit());
        vo.setStockQty(part.getStockQty());
        vo.setSafetyStock(part.getSafetyStock());
        vo.setLocation(part.getLocation());
        vo.setUnitPrice(part.getUnitPrice());
        vo.setSupplier(part.getSupplier());
        vo.setLowStock(part.isLowStock());
        vo.setStockRate(part.getStockRate());
        vo.setNote(part.getNote());
        return vo;
    }

    public ReplacementVO toReplacementVO(PartReplacement record) {
        ReplacementVO vo = new ReplacementVO();
        vo.setId(record.getId());
        vo.setPartId(record.getPartId());
        vo.setPartCode(record.getPartCode());
        vo.setPartName(record.getPartName());
        vo.setTurbineId(record.getTurbineId());
        vo.setUnitCode(record.getUnitCode());
        Turbine turbine = record.getTurbineId() == null
                ? null
                : turbineRepository.findById(record.getTurbineId()).orElse(null);
        if (turbine != null && turbine.getFarmId() != null) {
            WindFarm farm = farmRepository.findById(turbine.getFarmId()).orElse(null);
            vo.setFarmName(farm == null ? null : farm.getFarmName());
        }
        vo.setWorkOrderId(record.getWorkOrderId());
        vo.setOrderNo(record.getOrderNo());
        vo.setQty(record.getQty());
        vo.setReplaceTime(TimeText.dateTime(record.getReplaceTime()));
        vo.setOperator(record.getOperator());
        vo.setWarningFlag(record.getWarningFlag());
        vo.setStockAfter(record.getStockAfter());
        vo.setNote(record.getNote());
        markReviewIfConflicted(record, vo);
        return vo;
    }

    /**
     * 历史冲突数据识别（只读计算，绝不回写）：登记机位与工单所属机位不一致、
     * 或关联工单已不存在的旧记录，显示为「待核对」，由人工核实后处理，不做静默改写。
     */
    private void markReviewIfConflicted(PartReplacement record, ReplacementVO vo) {
        vo.setReviewFlag(false);
        if (record.getWorkOrderId() == null) {
            return;
        }
        WorkOrder order = orderRepository.findById(record.getWorkOrderId()).orElse(null);
        if (order == null) {
            vo.setReviewFlag(true);
            vo.setReviewNote("待核对：关联工单 " + record.getOrderNo() + "（id=" + record.getWorkOrderId()
                    + "）已不存在，无法核实机位一致性，请人工核对这条历史更换记录");
            return;
        }
        vo.setOrderUnitCode(order.getUnitCode());
        if (record.getTurbineId() != null && !record.getTurbineId().equals(order.getTurbineId())) {
            vo.setReviewFlag(true);
            vo.setReviewNote("待核对：登记机位 " + record.getUnitCode() + " 与工单 " + order.getOrderNo()
                    + " 所属机位 " + order.getUnitCode() + " 不一致（历史遗留数据，系统未做改动，请人工核对）");
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
