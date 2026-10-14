package com.company.member.modules.product.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.product.entity.Product;
import com.company.member.modules.product.entity.ProductCategory;
import com.company.member.modules.product.mapper.ProductCategoryMapper;
import com.company.member.modules.product.mapper.ProductMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 · 商品与服务
 */
@Tag(name = "管理端-商品")
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;

    @Operation(summary = "商品分页")
    @GetMapping
    @PreAuthorize("@ps.has('product:list')")
    public R<PageResult<Product>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long categoryId,
                                       @RequestParam(required = false) Integer productType,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<Product> w = new LambdaQueryWrapper<Product>()
                .eq(Product::getDeleted, 0)
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .eq(productType != null, Product::getProductType, productType)
                .eq(status != null, Product::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        q -> q.like(Product::getProductName, keyword).or().like(Product::getProductCode, keyword))
                .orderByAsc(Product::getSortNo)
                .orderByDesc(Product::getId);
        return R.ok(PageResult.of(productMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w)));
    }

    @Operation(summary = "全部上架商品（收银台取用）")
    @GetMapping("/all")
    @PreAuthorize("@ps.hasAny('product:list','order:add')")
    public R<List<Product>> all() {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getDeleted, 0)
                .eq(Product::getStatus, 1)
                .orderByAsc(Product::getSortNo)));
    }

    @Operation(summary = "商品详情")
    @GetMapping("/{id}")
    @PreAuthorize("@ps.has('product:query')")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productMapper.selectById(id));
    }

    @Operation(summary = "新增商品")
    @PostMapping
    @PreAuthorize("@ps.has('product:add')")
    public R<Product> create(@RequestBody Product product) {
        product.setId(null);
        product.setDeleted(0);
        product.setTenantId(0L);
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        productMapper.insert(product);
        return R.ok(product);
    }

    @Operation(summary = "修改商品")
    @PutMapping("/{id}")
    @PreAuthorize("@ps.has('product:edit')")
    public R<Product> update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        productMapper.updateById(product);
        return R.ok(productMapper.selectById(id));
    }

    @Operation(summary = "上下架")
    @PutMapping("/{id}/status")
    @PreAuthorize("@ps.has('product:edit')")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        Product p = new Product();
        p.setId(id);
        p.setStatus(status);
        productMapper.updateById(p);
        return R.ok();
    }

    @Operation(summary = "删除商品（逻辑删除）")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ps.has('product:remove')")
    public R<Void> remove(@PathVariable Long id) {
        Product p = new Product();
        p.setId(id);
        p.setDeleted(1);
        productMapper.updateById(p);
        return R.ok();
    }

    @Operation(summary = "商品分类列表")
    @GetMapping("/categories")
    @PreAuthorize("@ps.hasAny('product:query','order:add')")
    public R<List<ProductCategory>> categories() {
        return R.ok(categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getDeleted, 0)
                .eq(ProductCategory::getStatus, 1)
                .orderByAsc(ProductCategory::getSortNo)));
    }

    @Operation(summary = "新增分类")
    @PostMapping("/categories")
    @PreAuthorize("@ps.has('product:edit')")
    public R<ProductCategory> createCategory(@RequestBody ProductCategory category) {
        category.setId(null);
        category.setDeleted(0);
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        if (category.getParentId() == null) {
            category.setParentId(0L);
        }
        categoryMapper.insert(category);
        return R.ok(category);
    }
}
