package com.wind.farm.repository;

import com.wind.farm.domain.SparePart;
import com.wind.farm.dto.PartCategoryDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparePartRepository extends JpaRepository<SparePart, Long> {

    boolean existsByPartCode(String partCode);

    /** 行锁读（SELECT ... FOR UPDATE）：更换出库 / 步进器改库存时串行化并发，库存不得为负 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SparePart p where p.id = :id")
    Optional<SparePart> findWithLockById(@Param("id") Long id);

    List<SparePart> findAllByOrderByPartCodeAsc();

    List<SparePart> findByCategoryOrderByPartCodeAsc(String category);

    /** 分类库存分布：JPQL select new，DTO 必须有参数个数匹配的显式构造器 */
    @Query("select new com.wind.farm.dto.PartCategoryDTO(p.category, count(p), "
            + "sum(p.stockQty), "
            + "sum(case when p.stockQty < p.safetyStock then 1 else 0 end)) "
            + "from SparePart p group by p.category")
    List<PartCategoryDTO> statByCategory();
}
