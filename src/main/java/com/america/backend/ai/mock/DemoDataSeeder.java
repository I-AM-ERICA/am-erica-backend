package com.america.backend.ai.mock;

import java.time.DayOfWeek;
import java.time.LocalDate;

import com.america.backend.data.Policy;
import com.america.backend.data.PolicyRepository;
import com.america.backend.data.Review;
import com.america.backend.data.ReviewRepository;
import com.america.backend.data.Sale;
import com.america.backend.data.SaleRepository;
import com.america.backend.store.Store;
import com.america.backend.store.StoreRepository;
import com.america.backend.user.UserAccount;
import com.america.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** local 프로필에서 Swagger로 바로 시험할 수 있도록 데모 계정·매장·매출·리뷰·정책을 넣습니다. */
@Component
@ConditionalOnProperty(name = "ai.mock.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@america.local";
    public static final String DEMO_PASSWORD = "demo1234!";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository users;
    private final StoreRepository stores;
    private final SaleRepository sales;
    private final ReviewRepository reviews;
    private final PolicyRepository policies;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(UserRepository users, StoreRepository stores, SaleRepository sales,
                          ReviewRepository reviews, PolicyRepository policies, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.stores = stores;
        this.sales = sales;
        this.reviews = reviews;
        this.policies = policies;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(DEMO_EMAIL)) {
            return;
        }
        UserAccount user = users.save(new UserAccount(DEMO_EMAIL, passwordEncoder.encode(DEMO_PASSWORD), "데모 사장님"));
        Store store = stores.save(new Store(user, "에리카 분식", "분식", "경기 안산시", "경기도 안산시 상록구 한양대학로 55"));

        LocalDate today = LocalDate.now();
        for (int i = 41; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            long amount = (weekend ? 620_000L : 410_000L) + (i % 5) * 23_000L;
            sales.save(new Sale(store, date, amount, (int) (amount / 9_000)));
        }

        reviews.save(new Review(store, "네이버", 5, "떡볶이가 맵지 않고 달달해서 아이랑 먹기 좋았어요."));
        reviews.save(new Review(store, "배달의민족", 2, "배달이 40분 넘게 걸렸고 튀김이 눅눅했어요."));

        policies.save(new Policy("소상공인 정책자금(일반경영안정자금)", "소상공인시장진흥공단", Policy.NATIONWIDE, "금융",
                "업력 기준을 충족한 소상공인의 운영자금 융자 지원", "https://www.sbiz24.kr", null));
        policies.save(new Policy("소상공인 온라인 판로 지원", "중소벤처기업부", Policy.NATIONWIDE, "판로",
                "온라인 쇼핑몰 입점 및 라이브커머스 홍보 지원", "https://www.sbiz.or.kr", null));

        log.warn("[DEMO] 데모 계정 {} / {} (storeId={})", DEMO_EMAIL, DEMO_PASSWORD, store.getId());
    }
}
