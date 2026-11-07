# 达妮娅蓝色二形态

当前游戏使用 `Denia-Blue.bbmodel`。它有左右两片后裙、一束完整的后发、前围片和胸针。`Denia-Blue-Layered.bbmodel` 保留了另一版的后裙短覆片和分段发梢。两个文件均可用 Blockbench 的 Generic Model 格式打开，各自嵌有基础皮肤和附件贴图。

在仓库根目录运行 `node tools/export-domain-outfit.mjs`，会将当前版导出为模组内的 `girlfriend2.png`、`domain_outfit.png` 和 `domain_outfit.json`。用 `node tools/export-domain-outfit.mjs art/Denia-Blue.bbmodel build/outfit-preview` 可以先试导出到独立目录。对照分层版时，把命令中的工程名换为 `Denia-Blue-Layered.bbmodel`，并选择不同的输出目录。

附件组放在 `Body` 下。左右长裙片用 `tail_left`、`tail_right`，短覆片用 `tail_overlay_left`、`tail_overlay_right`。上段后发用 `back_lock`，发梢用 `back_lock_tip`。`front_apron` 是前围片，`brooch` 是胸针。新加的固定装饰可命名为 `fixed_名称`。每个附件方块用六面的矩形 UV 上色，静止旋转和动作支点设在组上。导出后重新构建模组，在新版客户端里检查走动和转头。
