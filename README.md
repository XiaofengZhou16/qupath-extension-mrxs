# QuPath MRXS：多通道免疫荧光浏览、分析与出图

在 QuPath 中直接打开受支持的多通道荧光 MRXS 切片，通过统一 IF 工作台完成高清浏览、调色、ROI 分析、历史结果查看和图片／表格导出。

**[下载当前预览版](https://github.com/XiaofengZhou16/qupath-extension-mrxs/releases/tag/v0.5.0-snapshot-20261114) · [直接下载插件 JAR](https://github.com/XiaofengZhou16/qupath-extension-mrxs/releases/download/v0.5.0-snapshot-20261114/qupath-extension-mrxs-0.5.0-SNAPSHOT.jar) · [English / technical reference](README.en.md)**

> 当前为实验性预览版，发布标签 `v0.5.0-snapshot-20261114`，插件内部版本为 `0.5.0-SNAPSHOT`。工程测试不代表分割或阳性判定准确率已验证，也不代表与 SaiViewer 算法等效。保留原始切片，并核对通道与抗体的对应关系。

## 1. 下载和安装

### 需要准备什么

- **QuPath 0.7.0**：从 [QuPath 官方发布页](https://github.com/qupath/qupath/releases) 安装适合系统的版本。当前安装与 GUI 验证基于 Apple Silicon macOS；其他平台尚未完成同等验收。
- 原始 `.mrxs` 文件及其**同名数据文件夹**，二者必须放在一起。插件当前支持已测试布局的 **8-bit、JPEG 压缩荧光 MRXS**，不保证所有扫描仪和编码变体兼容。
- 只浏览、调色或使用经典分割时不需要 AI 模型。InstanSeg AI 的额外准备见下文。

```text
切片目录/
├── sample.mrxs
└── sample/
    ├── Slidedat.ini
    ├── Index.dat
    └── Data*.dat
```

### 安装步骤

1. 下载 **`qupath-extension-mrxs-0.5.0-SNAPSHOT.jar`**。不要安装 `-sources.jar`，也不要把源码 ZIP 当作插件。
2. 在 QuPath 的 **Extensions → Manage extensions** 中确认扩展目录位置，然后关闭 QuPath。
3. 将主插件 JAR 复制到该扩展目录。更新时先把旧 MRXS 插件 JAR 移到目录外备份，避免同时加载两个版本。
4. 重新打开 QuPath，确认出现 **Extensions → MRXS → IF workflow / IF 工作台**。

仅使用插件不需要编译源码或安装 Gradle。复制到扩展目录后重启的方式已在隔离目录中验证。

需要校验下载文件时，发布页的 `SHA256SUMS.txt` 列出了附件校验值。macOS/Linux 可运行：

```bash
shasum -a 256 qupath-extension-mrxs-0.5.0-SNAPSHOT.jar
```

本版主插件 SHA-256：

```text
961d3c6383b6058f11708faa5f6e249af8b97f7ebf5fb859d129ed9d0c448d04
```

## 2. 第一次使用：先完成一个小 ROI

### 打开切片与选择区域

1. 在 QuPath 中打开 `.mrxs`，保留完整同名数据文件夹。若提示图像类型，选择 **Fluorescence**。
2. 确认读取到独立荧光通道，而不是只有 RGB 合成图。可通过 **Extensions → MRXS → Show compatibility report** 查看兼容性和存储信息。
3. 使用 QuPath 的矩形或多边形标注工具，在组织上画一个面积 ROI 并选中；首次分析建议从小区域开始。
4. 打开 **IF 工作台**，核对显示的切片名和 ROI。工作台有调色、分析、历史三个入口。

### 调色和导出图片

1. 进入工作台的调色与发表图窗口。勾选通道，调整伪彩、显示上下限和 gamma。
2. 点击 **Preview selected ROI / visible region** 预览；改变 ROI 后重新点击。用 **Save display profile / Load display profile** 保存或恢复显示参数。
3. 选择导出层级、通道组合、标签和比例尺；比例尺需要有效的物理像素标定。
4. 点击 **Check selected ROI dimensions** 检查尺寸，再点击 **Export publication panels…**，选择输出父目录。

导出包含合并／单通道／所选组合的 PNG 及复现信息。图片从源像素生成；提高 DPI 只改变打印尺寸，不会增加原始分辨率。发表前检查标签、比例尺和亮度截断，比较组使用一致的显示规则。

**调色只影响显示和图片外观，不改变原始强度或阳性判定阈值。** 导出的伪彩图片不能替代原始通道数据进行定量。

### 运行 IF 分析

1. 进入 **IF 分析与批处理**，确认已选中面积 ROI。
2. 选择参与表型统计的通道和原始强度阈值。DAPI 通常用于核定位，不自动作为目标抗体。阈值应依据阴性、单染等适当对照确定。
3. 选择输入方式：现有 QuPath 细胞、经典核分水岭，或 **InstanSeg AI**。经典分水岭不是 AI；核外扩只是细胞边界近似。
4. 点击底部 **Analyze selected ROI / 分析**，选择新的输出目录。运行中可取消；等待后台结束和控件恢复，不要把不完整结果当作阴性。
5. 检查结果、细胞轮廓和排除原因。人工修正工具默认隐藏，是可选步骤；自动分析不要求先手工修正。

分析输出包含 CSV、轮廓 GeoJSON、ROI、参数和来源记录。保留完整结果目录；仍带 **`INCOMPLETE`** 标记的输出不能使用。空分母留空，不能当作零；ROI 总面积也不等于组织面积。

### 使用 InstanSeg AI（可选）

1. 分别安装官方 [InstanSeg 扩展](https://github.com/qupath/qupath-extension-instanseg)及其所需 DJL 组件，按官方说明准备可信的 `fluorescence_nuclei_and_cells` 模型目录。
2. 重启 QuPath，在 IF 分析窗口选择 **InstanSeg AI**，指定模型目录并确认来源可信。模型目录应包含 `instanseg.pt` 和 `rdf.yaml`。
3. 选择模型输入通道与设备。AI 输入通道与表型统计通道是两套设置；核通道和边界通道应根据模型与样本选择。
4. 先用小 ROI 运行并检查结果，再批量处理。首次使用时 DJL 可能需要下载运行时；离线使用须提前准备。

已在本机验证的组合为 QuPath 0.7.0、InstanSeg 0.1.7、荧光模型 0.1.1（Apple Silicon）。**模型和 AI 运行时不包含在本插件 JAR 中**，不同组织、染色和设备需另行验证。

### 导出 Excel／Word 报告

在工作台进入 **历史结果与报告**，点击 **Choose results folder / 选择结果目录**，选择一次分析目录或其直接父目录。选中需要的记录，再点击 **Export selected reports / 导出所选记录**。

报告是所选结果的快照。以后修改 ROI 或重新分析，不会自动更新旧报告；应明确选择新的结果重新导出。Word 原生分页尚未完成验收，正式使用前检查排版。

## 3. 多切片、多 ROI 批处理

先建立 QuPath 项目，为每张切片画好并命名 ROI，**保存图像数据**。在分析窗口配置好模型和参数后，点击 **Project AI batch… / 项目 AI 批处理…**，读取已保存 ROI 并选择需要的任务。

批处理只读取已保存 ROI，不会因为没有 ROI 就自动做整片分析。所有选中切片需具有相同的通道名与顺序。完成后检查逐项状态，再导出批次 Excel／Word。

恢复旧批次必须保留原插件、模型及完整批次目录；不要修改身份指纹绕过版本检查。多个 ROI 不能自动视为独立动物／患者重复。

详见 **[项目批处理简明说明](docs/IF-BATCH-QUICKSTART-ZH.md)**。

## 4. 常见问题

| 现象 | 检查或处理 |
| --- | --- |
| 没有 MRXS 菜单 | 确认安装的是主 JAR、QuPath 为 0.7.0、扩展目录正确且没有重复旧版本，然后重启。 |
| 切片打不开或提示缺文件 | 检查 `.mrxs` 与同名目录、`Index.dat` 及所有数据文件；查看兼容性报告。不要只复制 `.mrxs`。 |
| 工作台调色／分析入口不可用 | 确认当前图像由本插件以 MRXS 多通道方式打开；历史浏览不要求打开切片。 |
| AI 不可用或模型加载失败 | 检查 InstanSeg/DJL 安装、可信模型目录及运行时准备情况；先查看 QuPath 日志，不要将失败解释为零细胞。 |
| 颜色调整后阳性率没变 | 正常：显示参数与定量阈值分开。需要改变判定时调整分析阈值并重新分析。 |
| 预览与新 ROI 不一致 | 重新点击预览按钮；预览使用包围框，最终导出按 ROI 掩膜处理。 |
| 导出目录有 `INCOMPLETE` | 分析／导出未成功完成，保留诊断记录并重试，不使用其中的半成品。 |
| 旧批次拒绝恢复 | 使用创建该批次的原插件和模型，或另建新批次；不绕过版本与来源检查。 |

反馈问题请提供插件版本、QuPath 版本、系统、操作步骤及脱敏日志。**不要把患者数据、私人实验报告或整张切片上传到公开 Issue。**

## 5. 详细说明与开发

- [IF 工作台日常操作](docs/IF-WORKFLOW-UI-ZH.md)
- [IF 参数、结果格式与可选复核](docs/IF-QUICKSTART-ZH.md)
- [英文技术说明与构建方法](README.en.md)
- [依赖与分发范围](DEPENDENCIES.md)
- [历史读取器验证记录](VALIDATION.md) · [参与开发](CONTRIBUTING.md)

当前预览版已完成本地构建、GUI／AI 工程回归、隔离扩展目录加载及两次独立启动检查；[对应源码的 GitHub CI 已通过](https://github.com/XiaofengZhou16/qupath-extension-mrxs/actions/runs/43602210236)。这些检查不建立生物学准确率。真实 30 张研究切片批次、其他平台和更多 MRXS 布局尚未完成同等验证。

本项目与 QuPath、3DHISTECH、CaseViewer、SaiViewer 或 OpenSlide 无隶属或背书关系。源码采用 [GPLv3](LICENSE)；模型和第三方软件适用各自条款。
