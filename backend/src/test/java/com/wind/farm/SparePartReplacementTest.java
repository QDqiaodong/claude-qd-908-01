package com.wind.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wind.farm.common.BizException;
import com.wind.farm.domain.PartReplacement;
import com.wind.farm.domain.SparePart;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.ReplacementRequest;
import com.wind.farm.dto.Views.ReplacementVO;
import com.wind.farm.repository.IsolationLogRepository;
import com.wind.farm.repository.IsolationPointRepository;
import com.wind.farm.repository.IsolationTemplateRepository;
import com.wind.farm.repository.PartReplacementRepository;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.SparePartRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkItemRepository;
import com.wind.farm.repository.WorkOrderRepository;
import com.wind.farm.service.SparePartService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 备件更换登记的关联写入链测试（H2 MySQL 模式 + JPA 自动建表）：
 * 机位 / 工单一致性校验、只传工单由工单补齐机位、旧请求兼容、提交时重新核对、
 * 并发库存不为负、requestId 幂等、历史冲突数据显示为待核对。
 */
@SpringBootTest
class SparePartReplacementTest {

    @Autowired private SparePartService parts;
    @Autowired private SparePartRepository partRepository;
    @Autowired private PartReplacementRepository replacementRepository;
    @Autowired private TurbineRepository turbineRepository;
    @Autowired private WindFarmRepository farmRepository;
    @Autowired private WorkOrderRepository orderRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private RunLogRepository runLogRepository;
    @Autowired private IsolationLogRepository isolationLogRepository;
    @Autowired private IsolationPointRepository isolationPointRepository;
    @Autowired private IsolationTemplateRepository isolationTemplateRepository;
    @Autowired private SafetyTicketRepository safetyTicketRepository;

    private Long farmId;
    private Long turbineA; // A01
    private Long turbineB; // A02
    private Long partId;

    @BeforeEach
    void setUp() {
        isolationLogRepository.deleteAll();
        isolationPointRepository.deleteAll();
        safetyTicketRepository.deleteAll();
        isolationTemplateRepository.deleteAll();
        workItemRepository.deleteAll();
        replacementRepository.deleteAll();
        orderRepository.deleteAll();
        runLogRepository.deleteAll();
        partRepository.deleteAll();
        turbineRepository.deleteAll();
        farmRepository.deleteAll();

        WindFarm farm = new WindFarm();
        farm.setFarmCode("F-RP-" + System.nanoTime());
        farm.setFarmName("更换测试风场");
        farm.setTimeZone("Asia/Shanghai");
        farm.setStatus("ACTIVE");
        farmId = farmRepository.save(farm).getId();

        turbineA = saveTurbine("A01").getId();
        turbineB = saveTurbine("A02").getId();

        SparePart part = new SparePart();
        part.setPartCode("SP-TEST-" + System.nanoTime());
        part.setPartName("测试备件");
        part.setCategory("ELECTRIC");
        part.setUnit("件");
        part.setStockQty(5);
        part.setSafetyStock(2);
        partId = partRepository.save(part).getId();
    }

    private Turbine saveTurbine(String unitCode) {
        Turbine t = new Turbine();
        t.setFarmId(farmId);
        t.setUnitCode(unitCode);
        t.setUnitName(unitCode + " 机位");
        t.setRatedPowerKw(new BigDecimal("3000"));
        t.setStatus("MAINTAIN");
        t.setLastPowerKw(BigDecimal.ZERO);
        t.setTodayEnergyKwh(BigDecimal.ZERO);
        t.setTotalEnergyKwh(BigDecimal.ZERO);
        t.setTotalHours(BigDecimal.ZERO);
        return turbineRepository.save(t);
    }

    private WorkOrder saveOrder(String orderNo, Long turbineId, String status) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo(orderNo);
        o.setTurbineId(turbineId);
        o.setFarmId(farmId);
        o.setUnitCode(turbineId.equals(turbineA) ? "A01" : "A02");
        o.setMaintType("ROUTINE");
        o.setTitle("工单-" + orderNo);
        o.setStatus(status);
        return orderRepository.save(o);
    }

    private ReplacementRequest req(Long turbineId, Long orderId, int qty) {
        ReplacementRequest r = new ReplacementRequest();
        r.setPartId(partId);
        r.setTurbineId(turbineId);
        r.setWorkOrderId(orderId);
        r.setQty(qty);
        r.setOperator("测试员");
        return r;
    }

    private int stock() {
        return partRepository.findById(partId).orElseThrow().getStockQty();
    }

    private void assertBiz(String msgContains, Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertTrue(ex.getMessage().contains(msgContains),
                "期望提示包含「" + msgContains + "」，实际：" + ex.getMessage());
    }

    // ---------------- 1. 机位 + 工单一致：正常登记 ----------------

    @Test
    void consistentTurbineAndOrderSucceeds() {
        WorkOrder order = saveOrder("WO-RP-1", turbineA, "WORKING");
        PartReplacement record = parts.replace(req(turbineA, order.getId(), 2));

        assertEquals(turbineA, record.getTurbineId());
        assertEquals("A01", record.getUnitCode());
        assertEquals(order.getId(), record.getWorkOrderId());
        assertEquals("WO-RP-1", record.getOrderNo());
        assertEquals(3, stock());
        assertEquals(3, record.getStockAfter());
    }

    // ---------------- 2. 机位 + 工单冲突：拒绝，且不扣库存、不写履历 ----------------

    @Test
    void conflictingTurbineAndOrderRejected() {
        WorkOrder order = saveOrder("WO-RP-2", turbineA, "WORKING");
        ReplacementRequest r = req(turbineB, order.getId(), 2);

        BizException ex = assertThrows(BizException.class, () -> parts.replace(r));
        // 提示必须指出应修正的机位 / 工单
        assertTrue(ex.getMessage().contains("WO-RP-2"), "提示应含工单号：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("A01"), "提示应含工单所属机位：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("A02"), "提示应含所选机位：" + ex.getMessage());

        assertEquals(5, stock(), "冲突拒绝后库存不得变化");
        assertTrue(replacementRepository.findAll().isEmpty(), "冲突拒绝后不得生成更换记录");
    }

    // ---------------- 3. 只传工单：由工单补齐机位 ----------------

    @Test
    void orderOnlyFillsTurbineFromOrder() {
        WorkOrder order = saveOrder("WO-RP-3", turbineB, "WORKING");
        PartReplacement record = parts.replace(req(null, order.getId(), 1));

        assertEquals(turbineB, record.getTurbineId(), "只传工单时机位应由工单补齐");
        assertEquals("A02", record.getUnitCode());
        assertEquals(4, stock());
    }

    // ---------------- 4. 旧请求兼容：只传机位 / 都不传 ----------------

    @Test
    void legacyTurbineOnlyAndNeitherStillWork() {
        PartReplacement byTurbine = parts.replace(req(turbineA, null, 1));
        assertEquals(turbineA, byTurbine.getTurbineId());
        assertNull(byTurbine.getWorkOrderId());

        PartReplacement neither = parts.replace(req(null, null, 1));
        assertNull(neither.getTurbineId());
        assertNull(neither.getWorkOrderId());

        assertEquals(3, stock());
        assertEquals(2, replacementRepository.findAll().size());
    }

    // ---------------- 5. 提交时重新核对：已闭环工单不可登记 ----------------

    @Test
    void closedOrderRejected() {
        WorkOrder order = saveOrder("WO-RP-5", turbineA, "CLOSED");
        assertBiz("不允许再登记更换", () -> parts.replace(req(turbineA, order.getId(), 1)));
        assertBiz("不允许再登记更换", () -> parts.replace(req(null, order.getId(), 1)));
        assertEquals(5, stock());
        assertTrue(replacementRepository.findAll().isEmpty());
    }

    // ---------------- 6. 提交时重新核对：数量不得超过当前库存 ----------------

    @Test
    void overStockRejected() {
        assertBiz("超过备件", () -> parts.replace(req(turbineA, null, 6)));
        assertEquals(5, stock(), "超量拒绝后库存不得变化，更不能为负");
        assertTrue(replacementRepository.findAll().isEmpty());
    }

    // ---------------- 7. 幂等：同一 requestId 只留一条履历、只扣一次库存 ----------------

    @Test
    void sameRequestIdOnlyOneRecord() {
        WorkOrder order = saveOrder("WO-RP-7", turbineA, "WORKING");
        ReplacementRequest r = req(turbineA, order.getId(), 2);
        r.setRequestId("req-idem-1");

        PartReplacement first = parts.replace(r);
        // 网络重试 / 重复点击：同一个 requestId 再提交
        PartReplacement replay = parts.replace(r);

        assertEquals(first.getId(), replay.getId(), "重试应返回已生效的那条记录");
        assertEquals(3, stock(), "同一登记只能扣一次库存");
        assertEquals(1, replacementRepository.findAll().size(), "同一登记只能留一条履历");
    }

    // ---------------- 8. 并发：两人同时用同一备件，库存不为负，只有一条生效 ----------------

    @Test
    void concurrentReplaceNeverNegative() throws InterruptedException {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // 库存 5，两人各登记 3：只能成功一个
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    ReplacementRequest r = req(turbineA, null, 3);
                    r.setRequestId("req-concurrent-" + idx);
                    parts.replace(r);
                    success.incrementAndGet();
                } catch (BizException e) {
                    failed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "库存 5 各扣 3 只能成功一笔");
        assertEquals(1, failed.get(), "另一笔必须失败而不是扣成负数");
        assertEquals(2, stock(), "失败方刷新后应看到真实剩余量");
        assertEquals(1, replacementRepository.findAll().size(), "只留一条有效履历");
    }

    // ---------------- 9. 并发 + 同一 requestId：网络重试并发也只留一条 ----------------

    @Test
    void concurrentSameRequestIdOnlyOneRecord() throws InterruptedException {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    ReplacementRequest r = req(turbineA, null, 2);
                    r.setRequestId("req-retry-same");
                    parts.replace(r);
                    success.incrementAndGet();
                } catch (Exception e) {
                    // 允许失败方报错，但绝不允许出现第二条记录或二次扣减
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(1, replacementRepository.findAll().size(), "同一 requestId 并发重试只留一条履历");
        assertEquals(3, stock(), "同一 requestId 并发重试只扣一次库存");
        assertTrue(success.get() >= 1, "至少一笔成功");
    }

    // ---------------- 10. 历史冲突数据：显示为待核对，不静默改写 ----------------

    @Test
    void conflictingLegacyRecordMarkedForReview() {
        WorkOrder order = saveOrder("WO-RP-10", turbineA, "WORKING");

        // 修复前 bug 留下的旧数据：A02 的更换量记到了 A01 的工单上
        PartReplacement legacy = new PartReplacement();
        legacy.setPartId(partId);
        legacy.setPartCode("SP-LEGACY");
        legacy.setPartName("历史备件");
        legacy.setTurbineId(turbineB);
        legacy.setUnitCode("A02");
        legacy.setWorkOrderId(order.getId());
        legacy.setOrderNo("WO-RP-10");
        legacy.setQty(1);
        legacy.setWarningFlag(false);
        legacy.setStockAfter(5);
        replacementRepository.save(legacy);

        // 一致的正常记录不应被标记
        PartReplacement normal = parts.replace(req(turbineA, order.getId(), 1));

        List<ReplacementVO> vos = parts.replacementVOs(null, null);
        ReplacementVO legacyVO = vos.stream().filter(v -> v.getId().equals(legacy.getId())).findFirst().orElseThrow();
        ReplacementVO normalVO = vos.stream().filter(v -> v.getId().equals(normal.getId())).findFirst().orElseThrow();

        assertTrue(legacyVO.getReviewFlag(), "冲突旧数据必须显示为待核对");
        assertTrue(legacyVO.getReviewNote().contains("待核对"));
        assertEquals("A01", legacyVO.getOrderUnitCode(), "应带出工单真实所属机位供人工核对");
        assertFalse(normalVO.getReviewFlag(), "一致记录不得误标");

        // 不静默改写：库里的旧行保持原样
        PartReplacement persisted = replacementRepository.findById(legacy.getId()).orElseThrow();
        assertEquals(turbineB, persisted.getTurbineId());
        assertEquals("A02", persisted.getUnitCode());
        assertNotEquals(persisted.getTurbineId(), order.getTurbineId());
    }

    // ---------------- 11. 工单所属机位被改后，旧冲突随之暴露 / 新登记按最新机位校验 ----------------

    @Test
    void submitTimeRecheckUsesLatestOrderTurbine() {
        WorkOrder order = saveOrder("WO-RP-11", turbineA, "WORKING");
        // 登记前工单被改派到 A02（页面还停在 A01）
        order.setTurbineId(turbineB);
        order.setUnitCode("A02");
        orderRepository.save(order);

        // 页面旧数据（A01）提交：必须按最新所属机位拒绝
        assertBiz("不一致", () -> parts.replace(req(turbineA, order.getId(), 1)));
        // 只传工单：按最新所属机位补齐
        PartReplacement record = parts.replace(req(null, order.getId(), 1));
        assertEquals(turbineB, record.getTurbineId());
        assertEquals("A02", record.getUnitCode());
    }
}
