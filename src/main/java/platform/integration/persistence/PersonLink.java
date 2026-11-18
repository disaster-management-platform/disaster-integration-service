package platform.integration.persistence;
import jakarta.persistence.*;
@Entity @Table(name="person_links",schema="integration")
public class PersonLink {
    @Id @Column(length=240) public String id;
    @Column(nullable=false,length=200) public String organization;
    @Column(name="person_id",nullable=false,length=30) public String personId;
    @Column(name="external_uuid",nullable=false) public java.util.UUID externalUuid;
    @Column(name="disaster_id") public Integer disasterId;
    @Column(name="event_id",length=30) public String eventId;
}
