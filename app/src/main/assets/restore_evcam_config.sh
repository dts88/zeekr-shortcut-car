#!/system/bin/sh
#
# EVCam 白名单配置恢复脚本
# 用途：从备份恢复车机系统的白名单配置文件
# 从应用内通过 su 执行
#

# ==================== 配置区域 ====================
EXPECTED_DEVICE="e245"

# 文件路径
LIFECTL_FILE="/system/etc/geely_lifectl_start_list.xml"
WHITELIST_FILE="/system/etc/ecarx_str_policies.xml"
BGMS_FILE="/vendor/etc/bgms_config.xml"

# 备份目录
BACKUP_DIR="/data/media/11/evcam_backup"

# ==================== 工具函数 ====================

print_info() {
    echo "[INFO] $1"
}

print_success() {
    echo "[OK] $1"
}

print_warning() {
    echo "[WARN] $1"
}

print_error() {
    echo "[ERROR] $1"
}

print_step() {
    echo ""
    echo "===== $1 ====="
}

# ==================== 步骤 1：检查设备型号 ====================

check_device_model() {
    print_step "步骤 1/5：检查设备型号"

    DEVICE_PRODUCT=$(getprop ro.product.device 2>/dev/null)
    DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null)
    DEVICE_NAME=$(getprop ro.product.name 2>/dev/null)
    BUILD_PRODUCT=$(getprop ro.build.product 2>/dev/null)

    print_info "设备信息："
    print_info "  ro.product.device: $DEVICE_PRODUCT"
    print_info "  ro.product.model: $DEVICE_MODEL"
    print_info "  ro.product.name: $DEVICE_NAME"
    print_info "  ro.build.product: $BUILD_PRODUCT"

    if echo "$DEVICE_PRODUCT $DEVICE_MODEL $DEVICE_NAME $BUILD_PRODUCT" | grep -qi "$EXPECTED_DEVICE"; then
        print_success "设备型号匹配：检测到 E245 设备"
        return 0
    else
        print_error "设备型号不匹配！"
        print_error "期望：包含 '$EXPECTED_DEVICE'"
        print_error "实际：$DEVICE_PRODUCT / $DEVICE_MODEL / $DEVICE_NAME"
        return 1
    fi
}

# ==================== 步骤 2：检查写入权限 ====================

check_write_permission() {
    print_step "步骤 2/5：检查 system 和 vendor 分区写入权限"

    CURRENT_USER=$(id -u 2>/dev/null)
    if [ "$CURRENT_USER" != "0" ]; then
        print_error "当前不具有系统写入权限 (uid=$CURRENT_USER)"
        print_error "请确保设备已开启 USB 调试"
        return 1
    fi
    print_success "已获取系统权限 (uid=0)"

    # 将 SELinux 设为宽容模式，避免 "inaccessible or not found" 错误
    SELINUX_STATUS=$(getenforce 2>/dev/null)
    print_info "当前 SELinux 状态: $SELINUX_STATUS"
    if [ "$SELINUX_STATUS" = "Enforcing" ]; then
        setenforce 0 2>/dev/null
        NEW_STATUS=$(getenforce 2>/dev/null)
        if [ "$NEW_STATUS" = "Permissive" ]; then
            print_success "SELinux 已设为 Permissive（宽容模式）"
        else
            print_warning "SELinux 设置失败，后续操作可能受 SELinux 拦截"
        fi
    else
        print_info "SELinux 已处于非 Enforcing 状态，无需修改"
    fi

    print_info "尝试 remount system/vendor 分区..."
    if [ -x /system/bin/remount ]; then
        print_info "调用 /system/bin/remount ..."
        /system/bin/remount 2>/dev/null
    fi

    mount -o remount,rw / 2>/dev/null
    mount -o remount,rw /system 2>/dev/null

    TEST_FILE="/system/.evcam_write_test_$$"
    if touch "$TEST_FILE" 2>/dev/null; then
        rm -f "$TEST_FILE" 2>/dev/null
        print_success "system 分区可写"
    else
        print_error "system 分区不可写！"
        print_error "请先在 PC 端执行以下命令后再重试："
        print_error "  adb root && adb remount"
        return 1
    fi

    mount -o remount,rw /vendor 2>/dev/null

    TEST_FILE="/vendor/.evcam_write_test_$$"
    if touch "$TEST_FILE" 2>/dev/null; then
        rm -f "$TEST_FILE" 2>/dev/null
        print_success "vendor 分区可写"
    else
        print_error "vendor 分区不可写！"
        print_error "请先在 PC 端执行以下命令后再重试："
        print_error "  adb root && adb remount"
        return 1
    fi

    return 0
}

# ==================== 步骤 3：检查备份文件 ====================

check_backup_files() {
    print_step "步骤 3/5：检查备份文件"

    if [ ! -d "$BACKUP_DIR" ]; then
        print_error "备份目录不存在：$BACKUP_DIR"
        print_error "请先执行「一键配置」创建备份后再恢复"
        return 1
    fi

    FOUND_ANY=0

    # 检查 bgms_config.xml 备份（最关键）
    BGMS_LATEST=$(ls -t "$BACKUP_DIR"/bgms_config.xml.bak.* 2>/dev/null | head -1)
    if [ -n "$BGMS_LATEST" ] && [ -f "$BGMS_LATEST" ]; then
        print_success "找到 bgms_config.xml 备份: $(basename "$BGMS_LATEST")"
        if grep -q '<device' "$BGMS_LATEST" && grep -q 'com.geely.avm_app' "$BGMS_LATEST"; then
            print_success "  备份文件验证通过（包含 avm_app 条目）"
            FOUND_ANY=1
        else
            print_error "  备份文件已损坏，跳过"
            BGMS_LATEST=""
        fi
    else
        print_warning "未找到 bgms_config.xml 备份"
    fi

    # 检查 geely_lifectl_start_list.xml 备份
    LIFECTL_LATEST=$(ls -t "$BACKUP_DIR"/geely_lifectl_start_list.xml.bak.* 2>/dev/null | head -1)
    if [ -n "$LIFECTL_LATEST" ] && [ -f "$LIFECTL_LATEST" ]; then
        print_success "找到 geely_lifectl_start_list.xml 备份: $(basename "$LIFECTL_LATEST")"
        if grep -q '<services>' "$LIFECTL_LATEST" && grep -q '</services>' "$LIFECTL_LATEST"; then
            print_success "  备份文件验证通过"
            FOUND_ANY=1
        else
            print_error "  备份文件已损坏，跳过"
            LIFECTL_LATEST=""
        fi
    else
        print_warning "未找到 geely_lifectl_start_list.xml 备份"
    fi

    # 检查 ecarx_str_policies.xml 备份
    WHITELIST_LATEST=$(ls -t "$BACKUP_DIR"/ecarx_str_policies.xml.bak.* 2>/dev/null | head -1)
    if [ -n "$WHITELIST_LATEST" ] && [ -f "$WHITELIST_LATEST" ]; then
        print_success "找到 ecarx_str_policies.xml 备份: $(basename "$WHITELIST_LATEST")"
        if grep -q '<whitelist>' "$WHITELIST_LATEST" && grep -q '</whitelist>' "$WHITELIST_LATEST"; then
            print_success "  备份文件验证通过"
            FOUND_ANY=1
        else
            print_error "  备份文件已损坏，跳过"
            WHITELIST_LATEST=""
        fi
    else
        print_warning "未找到 ecarx_str_policies.xml 备份"
    fi

    if [ "$FOUND_ANY" = "0" ]; then
        print_error "未找到任何有效的备份文件"
        return 1
    fi

    return 0
}

# ==================== 步骤 4：恢复文件 ====================

restore_files() {
    print_step "步骤 4/5：恢复配置文件"

    RESTORE_COUNT=0

    if [ -n "$BGMS_LATEST" ]; then
        print_info "恢复 $BGMS_FILE ..."
        cp "$BGMS_LATEST" "$BGMS_FILE"
        if [ $? -eq 0 ]; then
            chmod 644 "$BGMS_FILE"
            print_success "已恢复 $BGMS_FILE"
            RESTORE_COUNT=$((RESTORE_COUNT + 1))
        else
            print_error "恢复 $BGMS_FILE 失败"
        fi
    fi

    if [ -n "$LIFECTL_LATEST" ]; then
        print_info "恢复 $LIFECTL_FILE ..."
        cp "$LIFECTL_LATEST" "$LIFECTL_FILE"
        if [ $? -eq 0 ]; then
            chmod 644 "$LIFECTL_FILE"
            print_success "已恢复 $LIFECTL_FILE"
            RESTORE_COUNT=$((RESTORE_COUNT + 1))
        else
            print_error "恢复 $LIFECTL_FILE 失败"
        fi
    fi

    if [ -n "$WHITELIST_LATEST" ]; then
        print_info "恢复 $WHITELIST_FILE ..."
        cp "$WHITELIST_LATEST" "$WHITELIST_FILE"
        if [ $? -eq 0 ]; then
            chmod 644 "$WHITELIST_FILE"
            print_success "已恢复 $WHITELIST_FILE"
            RESTORE_COUNT=$((RESTORE_COUNT + 1))
        else
            print_error "恢复 $WHITELIST_FILE 失败"
        fi
    fi

    sync

    if [ "$RESTORE_COUNT" -eq 0 ]; then
        return 1
    fi

    print_success "已恢复 $RESTORE_COUNT 个文件"
    return 0
}

# ==================== 步骤 5：验证恢复结果 ====================

verify_restore() {
    print_step "步骤 5/5：验证恢复结果"

    VERIFY_FAILED=0

    if [ -n "$BGMS_LATEST" ]; then
        print_info "验证 $BGMS_FILE ..."
        if grep -q '<device' "$BGMS_FILE" && grep -q '</device>' "$BGMS_FILE"; then
            if grep -q 'com.geely.avm_app' "$BGMS_FILE"; then
                print_success "bgms_config.xml 验证通过（avm_app 条目完整）"
            else
                print_error "bgms_config.xml 验证失败：avm_app 条目缺失"
                VERIFY_FAILED=1
            fi
        else
            print_error "bgms_config.xml 验证失败：XML 结构损坏"
            VERIFY_FAILED=1
        fi
    fi

    echo ""

    if [ -n "$LIFECTL_LATEST" ]; then
        print_info "验证 $LIFECTL_FILE ..."
        if grep -q '<services>' "$LIFECTL_FILE" && grep -q '</services>' "$LIFECTL_FILE"; then
            print_success "geely_lifectl_start_list.xml 验证通过"
        else
            print_error "geely_lifectl_start_list.xml 验证失败：XML 结构损坏"
            VERIFY_FAILED=1
        fi
    fi

    echo ""

    if [ -n "$WHITELIST_LATEST" ]; then
        print_info "验证 $WHITELIST_FILE ..."
        if grep -q '<whitelist>' "$WHITELIST_FILE" && grep -q '</whitelist>' "$WHITELIST_FILE"; then
            print_success "ecarx_str_policies.xml 验证通过"
        else
            print_error "ecarx_str_policies.xml 验证失败：XML 结构损坏"
            VERIFY_FAILED=1
        fi
    fi

    echo ""

    if [ "$VERIFY_FAILED" = "1" ]; then
        print_error "验证失败！部分文件可能未正确恢复"
        return 1
    fi

    return 0
}

# ==================== 主函数 ====================

main() {
    echo ""
    echo "=============================================="
    echo "  EVCam 白名单配置恢复脚本"
    echo "  从备份恢复系统白名单配置"
    echo "=============================================="
    echo ""
    echo "将恢复以下文件（如有备份）："
    echo "  1. $LIFECTL_FILE (服务启动列表)"
    echo "  2. $WHITELIST_FILE (Ecarx STR白名单)"
    echo "  3. $BGMS_FILE (BGMS后台白名单)"
    echo ""
    echo "备份目录：$BACKUP_DIR"
    echo ""

    # 步骤 1：检查设备型号
    check_device_model
    if [ $? -ne 0 ]; then
        exit 1
    fi

    # 步骤 2：检查写入权限
    check_write_permission
    if [ $? -ne 0 ]; then
        exit 1
    fi

    # 步骤 3：检查备份文件
    check_backup_files
    if [ $? -ne 0 ]; then
        exit 1
    fi

    # 步骤 4：恢复文件
    restore_files
    if [ $? -ne 0 ]; then
        print_error "恢复失败！"
        exit 1
    fi

    # 步骤 5：验证恢复
    verify_restore
    if [ $? -ne 0 ]; then
        print_error "验证失败！"
        exit 1
    fi

    # 完成
    echo ""
    echo "=============================================="
    print_success "恢复完成！请重启车机使配置生效"
    echo "=============================================="
    echo ""
    print_info "恢复后 EVCam 的白名单配置将被移除"
    print_info "如需重新配置，请再次使用「一键配置」功能"
    echo ""
}

# 执行主函数
main "$@"
