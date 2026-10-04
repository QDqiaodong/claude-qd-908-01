package com.wind.farm.repository;

import com.wind.farm.domain.PartReplacement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartReplacementRepository extends JpaRepository<PartReplacement, Long> {

    List<PartReplacement> findAllByOrderByReplaceTimeDesc();

    List<PartReplacement> findByTurbineIdOrderByReplaceTimeDesc(Long turbineId);

    List<PartReplacement> findByPartIdOrderByReplaceTimeDesc(Long partId);

    /** 幂等键查询：同一 requestId 的更换登记至多一条（唯一索引兜底） */
    Optional<PartReplacement> findByRequestId(String requestId);
}
