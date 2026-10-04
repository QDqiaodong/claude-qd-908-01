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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 备件与更换：备件编号唯一、安全库存不得为负、库存不得为负；
 * 更换数量 > 0 且不得超过当前库存；库存低于安全库存时仍可出库但要打预警标记。
 */
@Service
public class SparePartService {

    private final SparePartRepository partRepository;
    private final PartReplacementRepository replacementRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final WorkOrderRepository orderRepository;

    public SparePartService(SparePartRepository partRepository, PartReplacementRepository replacementRepository,
            TurbineRepository turbineRepository, WindFarmRepository farmRepository,
            WorkOrderRepository orderRepository) {
        this.partRepository = partRepository;
        this.replacementRepository = replacementRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.orderRepository = orderRepository;
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

    /** 步进器改库存：− / + 直接增减当前库存 */
    @Transactional
    public SparePart changeStock(Long id, Integer delta) {
        if (delta == null) {
            throw new BizException("库存变化量不能为空");
        }
        if (delta > Dict.MAX_REPLACE_QTY || delta < -Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次库存调整不得超过 " + Dict.MAX_REPLACE_QTY);
        }
        SparePart part = get(id);
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

    /** 更换登记：数量 > 0 且不得超过当前库存；出库后低于安全库存打预警标记 */
    @Transactional
    public PartReplacement replace(ReplacementRequest request) {
        if (request.getPartId() == null) {
            throw new BizException("请选择要更换的备件");
        }
        SparePart part = get(request.getPartId());
        if (request.getQty() == null || request.getQty() <= 0) {
            throw new BizException("更换数量必须大于 0");
        }
        if (request.getQty() > Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次更换数量不得超过 " + Dict.MAX_REPLACE_QTY);
        }
        int stock = nvl(part.getStockQty());
        if (request.getQty() > stock) {
            throw new BizException("更换数量 " + request.getQty() + " 超过当前库存 " + stock + " " + part.getUnit());
        }

        Turbine turbine = request.getTurbineId() == null
                ? null
                : turbineRepository.findById(request.getTurbineId()).orElse(null);
        if (request.getTurbineId() != null && turbine == null) {
            throw new BizException("机位不存在：" + request.getTurbineId());
        }
        WorkOrder order = request.getWorkOrderId() == null
                ? null
                : orderRepository.findById(request.getWorkOrderId()).orElse(null);
        if (request.getWorkOrderId() != null && order == null) {
            throw new BizException("检修工单不存在：" + request.getWorkOrderId());
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
        return vo;
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
