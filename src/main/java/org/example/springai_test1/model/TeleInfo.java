package org.example.springai_test1.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 通讯录实体，映射到 BSG_TETE_INFO 表
 * 存储联系人姓名、邮箱、电话等信息
 */
@Entity
@Table(name = "BSG_TETE_INFO")
public class TeleInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 联系人姓名 */
    @Column(nullable = false)
    private String name;

    /** 年龄 */
    private Integer age;

    /** 电话号码 */
    private String phone;

    /** 邮箱地址 */
    private String email;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public TeleInfo() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
