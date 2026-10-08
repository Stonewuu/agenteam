const PptxGenJS = require("pptxgenjs");

async function main() {
  const presentation = new PptxGenJS();
  presentation.layout = "LAYOUT_WIDE";
  presentation.author = "AgenTeam";
  presentation.subject = "办公环境验证";
  const slide = presentation.addSlide();
  slide.addText("中文演示文稿验证", {
    x: 0.7,
    y: 0.6,
    w: 12,
    h: 0.7,
    fontFace: "Noto Sans CJK SC",
    fontSize: 28,
    color: "163E5B"
  });
  slide.addText("Node.js 创建原生可编辑内容", {
    x: 0.7,
    y: 1.6,
    w: 11,
    h: 0.7,
    fontFace: "Noto Sans CJK SC",
    fontSize: 20
  });
  slide.addChart(presentation.ChartType.bar, [{name: "数量", labels: ["第一项", "第二项"], values: [2, 3]}],
    {x: 0.7, y: 2.7, w: 10.5, h: 3.8, showLegend: false, showTitle: false, catAxisLabelFontFace: "Noto Sans CJK SC"});
  await presentation.writeFile({fileName: "/workspace/outputs/sample.pptx"});
}

main().catch((failure) => {
  console.error("生成演示文稿样本失败", failure);
  process.exitCode = 1;
});
