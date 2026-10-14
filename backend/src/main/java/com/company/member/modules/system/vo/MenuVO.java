package com.company.member.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点（前端动态路由）
 */
@Data
@Schema(description = "菜单节点")
public class MenuVO implements Serializable {

    private Long id;
    private Long parentId;
    /** 1目录 2菜单 */
    private Integer menuType;
    private String menuName;
    private String permCode;

    @Schema(description = "前端路由 path")
    private String path;
    @Schema(description = "前端组件路径")
    private String component;
    private String icon;
    private Integer sortNo;
    private Boolean hidden;

    private List<MenuVO> children = new ArrayList<>();
}
