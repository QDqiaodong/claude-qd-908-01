package com.wind.farm.repository;

import com.wind.farm.domain.SparePart;
import com.wind.farm.dto.PartCategoryDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SparePartRepository extends JpaRepository<SparePart, Long> {

    boolean existsByPartCode(String partCode);

    List<SparePart> findAllByOrderByPartCodeAsc();

    List<SparePart> findByCategoryOrderByPartCodeAsc(String category);

    /** 分类库存分布：JPQL select new，DTO 必须有参数个数匹配的显式构造器 */
    @Query("select new com.wind.farm.dto.PartCategoryDTO(p.category, count(p), "
            + "sum(p.stockQty), "
            + "sum(case when p.stockQty < p.safetyStock then 1 else 0 end)) "
            + "from SparePart p group by p.category")
    List<PartCategoryDTO> statByCategory();
}
