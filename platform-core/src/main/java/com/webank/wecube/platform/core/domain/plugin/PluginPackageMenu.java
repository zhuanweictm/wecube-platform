package com.webank.wecube.platform.core.domain.plugin;

import com.fasterxml.jackson.annotation.JsonBackReference;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;

import javax.persistence.*;

import static com.webank.wecube.platform.core.utils.Constants.KEY_COLUMN_DELIMITER;

@Entity
@Table(name = "plugin_package_menus")
public class PluginPackageMenu implements Comparable<PluginPackageMenu> {

    @Id
    private String id;

    @JsonBackReference
    @ManyToOne(cascade = { CascadeType.PERSIST, CascadeType.DETACH, CascadeType.MERGE, CascadeType.REFRESH })
    @JoinColumn(name = "plugin_package_id")
    private PluginPackage pluginPackage;

    @Column
    private String code;

    @Column
    private String category;

    @Column
    private String displayName;

    @Column
    private String path;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @PrePersist
    public void initId() {
        if (null == this.id || this.id.trim().equals("")) {
            this.id = String.join(KEY_COLUMN_DELIMITER,
                    null != pluginPackage ? pluginPackage.getName() : null,
                    null != pluginPackage ? pluginPackage.getVersion() : null,
                    code,
                    category
            );
        }
    }

    public PluginPackage getPluginPackage() {
        return pluginPackage;
    }

    public void setPluginPackage(PluginPackage pluginPackage) {
        this.pluginPackage = pluginPackage;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public PluginPackageMenu() {
        super();
    }

    public PluginPackageMenu(String id, PluginPackage pluginPackage, String code, String category, String displayName,
                             String path) {
        this.id = id;
        this.pluginPackage = pluginPackage;
        this.code = code;
        this.category = category;
        this.displayName = displayName;
        this.path = path;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toStringExclude(this, new String[] { "pluginPackage" });
    }

    @Override
    public int compareTo(PluginPackageMenu compareObject) {
        return this.getId().compareTo(compareObject.getId());
    }
}
