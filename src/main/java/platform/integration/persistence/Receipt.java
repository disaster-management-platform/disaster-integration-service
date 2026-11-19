package platform.integration.persistence;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.OffsetDateTime;
@Entity @Table(name="receipts",schema="integration")
public class Receipt {
    @Id public UUID id;
    @Column(nullable=false,length=200) public String organization;
    @Column(nullable=false,length=200) public String actor;
    @Column(nullable=false,length=12) public String kind;
    @Column(nullable=false,columnDefinition="text") public String command;
    @Column(name="external_uuid",nullable=false) public UUID externalUuid;
    @Column(name="target_person",length=30) public String targetPerson;
    @Column(name="target_resource",length=64) public String targetResource;
    @Column(nullable=false) public boolean dispatched;
    public UUID lease;
    @Column(name="lease_until") public OffsetDateTime leaseUntil;
    @Column(columnDefinition="text") public String result;
    public Integer status;
    @Column(name="disaster_id") public Integer disasterId;
}
