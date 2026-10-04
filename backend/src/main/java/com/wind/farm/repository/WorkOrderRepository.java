package com.wind.farm.repository;

import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.WorkOrderStatusDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    Optional<WorkOrder> findByOrderNo(String orderNo);

    /** 行锁读（SELECT ... FOR UPDATE）：更换登记期间锁定工单，防止状态 / 所属机位被并发改写 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> findWithLockById(@Param("id") Long id);

    List<WorkOrder> findAllByOrderByOrderNoAsc();

    List<WorkOrder> findByTurbineIdOrderByOrderNoAsc(Long turbineId);

    List<WorkOrder> findByFarmIdOrderByOrderNoAsc(Long farmId);

    List<WorkOrder> findByStatusOrderByOrderNoAsc(String status);

    long countByTurbineIdAndStatus(Long turbineId, String status);

    long countByStatus(String status);

    /** 检修类型分布：JPQL select new，DTO 必须有参数个数匹配的显式构造器 */
    @Query("select new com.wind.farm.dto.WorkOrderStatusDTO(w.maintType, count(w)) "
            + "from WorkOrder w group by w.maintType")
    List<WorkOrderStatusDTO> statByMaintType();

    /** 工单状态分布 */
    @Query("select new com.wind.farm.dto.WorkOrderStatusDTO(w.status, count(w)) "
            + "from WorkOrder w group by w.status")
    List<WorkOrderStatusDTO> statByStatus();
}
