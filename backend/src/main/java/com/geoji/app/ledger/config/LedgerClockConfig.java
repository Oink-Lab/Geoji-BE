package com.geoji.app.ledger.config;

import org.springframework.context.annotation.*;
import java.time.*;

@Configuration
class LedgerClockConfig {
    @Bean Clock ledgerClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
