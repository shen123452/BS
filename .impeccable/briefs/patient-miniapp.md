# Surface brief — 患者微信小程序

Scope: 微信原生小程序患者端，覆盖登录与隐私确认、今日概览、SMBG 录入与记录、CGM 导入、趋势分析、AGP/GMI、用药、预警、问答、个人中心。Mode: Operate.

Audience/job: 患者在日常生活中快速记录一次血糖、确认今天的状态、看懂近期趋势；需要大字、高对比、明确状态和不被装饰干扰的阅读顺序。

Chosen direction: 真实的患者健康记录小程序（category standard，按生产应用的方式直接做）。浅色背景、蓝色品牌、白色卡片、清晰的数据层级；不采用暗色概念页、超大数字、装饰刻度或英文小标签。

## Direction contract

THESIS: 一款患者每天会打开、能快速完成记录和查看的血糖管理小程序，不是概念展示页。

OWN-WORLD: 背景 #F4F6F8、白色 12rpx 圆角卡片、#E1E7EE 细边框、医院蓝 #0B5C8E；系统中文 sans；数值用 tabular numerals，字号适中；状态用浅色标签与文字共同表达。

STORY: 打开先看到今天两条来源的最近读数；主按钮记录一次血糖；向下是今日用药和最近测量；分析、问答和个人中心各有清晰入口。

FIRST VIEWPORT: 顶部医院蓝读数区（今日血糖、日期、真实/演示标识、指尖血糖与连续监测两列最近值）；下方满宽蓝色主操作“记录一次血糖”；再下方是今日记录摘要、今日用药、最近测量和长期分析入口。

FORM: 患者健康记录应用 / 标准移动端组件；seed key 不适用（用户否决前一概念方向后按生产应用直接实现）。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
