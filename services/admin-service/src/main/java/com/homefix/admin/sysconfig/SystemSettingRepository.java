package com.homefix.admin.sysconfig;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository backing the {@link JpaSystemSettingStore}. */
public interface SystemSettingRepository extends JpaRepository<SystemSettingEntity, String> {
}
