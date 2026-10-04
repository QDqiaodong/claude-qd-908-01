package com.wind.farm.repository;

import com.wind.farm.domain.PartReplacement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartReplacementRepository extends JpaRepository<PartReplacement, Long> {

    List<PartReplacement> findAllByOrderByReplaceTimeDesc();

    List<PartReplacement> findByTurbineIdOrderByReplaceTimeDesc(Long turbineId);

    List<PartReplacement> findByPartIdOrderByReplaceTimeDesc(Long partId);
}
