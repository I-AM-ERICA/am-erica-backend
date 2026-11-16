package com.america.backend.user;

import com.america.backend.store.Store;
import jakarta.persistence.*;

@Entity
@Table(name = "account_settings")
public class AccountSettings {
    @Id @Column(name = "user_id")
    private Long userId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private UserAccount user;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "store_id")
    private Store store;
    @Column(name = "owner_name", length = 50)
    private String ownerName;
    @Column(name = "policy_gender", nullable = false, length = 4)
    private String policyGender = "boy";
    @Column(name = "marketing_gender", nullable = false, length = 4)
    private String marketingGender = "girl";
    @Column(name = "customer_gender", nullable = false, length = 4)
    private String customerGender = "girl";
    @Column(name = "sales_gender", nullable = false, length = 4)
    private String salesGender = "boy";

    protected AccountSettings() {}
    public AccountSettings(UserAccount user) { this.user = user; }
    public Store getStore() { return store; }
    public String getOwnerName() { return ownerName; }
    public String getPolicyGender() { return policyGender; }
    public String getMarketingGender() { return marketingGender; }
    public String getCustomerGender() { return customerGender; }
    public String getSalesGender() { return salesGender; }
    public void setStore(Store store, String ownerName) { this.store = store; this.ownerName = ownerName; }
    public void setGender(String assistant, String gender) {
        switch (assistant) {
            case "policy" -> policyGender = gender;
            case "marketing" -> marketingGender = gender;
            case "customer" -> customerGender = gender;
            case "sales" -> salesGender = gender;
            default -> throw new IllegalArgumentException("Unknown assistant");
        }
    }
}
