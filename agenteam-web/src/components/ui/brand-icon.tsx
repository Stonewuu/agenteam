import Image from "next/image";

const brands = {
  feishu: {src: "/brands/feishu.png", name: "飞书"},
} as const;

/** 品牌标志统一使用已核对来源的本地资源，保留官方形状和颜色。 */
export function BrandIcon({brand, size = 24, className, decorative = true}: {
  brand: keyof typeof brands;
  size?: number;
  className?: string;
  decorative?: boolean;
}) {
  const asset = brands[brand];
  return <Image src={asset.src} width={size} height={size} alt={decorative ? "" : asset.name}
                aria-hidden={decorative || undefined} className={className} unoptimized/>;
}
