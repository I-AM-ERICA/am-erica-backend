package com.america.backend.data;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    @Query("""
            select p from Policy p
            where p.applyEnd >= :today and (p.applyStart is null or p.applyStart <= :today)
            order by p.applyEnd asc, p.id asc
            """)
    List<Policy> findOpen(@Param("today") java.time.LocalDate today);

    @Query("""
            select p from Policy p
            where (:allRegions = true or p.region = :region or p.region = '전국')
              and (lower(p.title) like :pattern or lower(p.summary) like :pattern)
            order by p.id desc
            """)
    List<Policy> search(@Param("allRegions") boolean allRegions, @Param("region") String region,
                        @Param("pattern") String pattern, Pageable pageable);
}
