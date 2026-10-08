import type {SVGProps} from "react";

/** 四个独立形体围出共同中心，复用用户选定的“协作”标志。 */
export function BrandMark({size = 28, ...props}: SVGProps<SVGSVGElement> & { size?: number }) {
  return <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" width={size} height={size} fill="currentColor"
              aria-hidden="true" focusable="false" {...props}>
    <path d="M29 12H45V33L33 45H29C19.61 45 12 37.61 12 28.5S19.61 12 29 12Z"/>
    <path d="M29 12H45V33L33 45H29C19.61 45 12 37.61 12 28.5S19.61 12 29 12Z" transform="rotate(90 48 48)"/>
    <path d="M29 12H45V33L33 45H29C19.61 45 12 37.61 12 28.5S19.61 12 29 12Z" transform="rotate(180 48 48)"/>
    <path d="M29 12H45V33L33 45H29C19.61 45 12 37.61 12 28.5S19.61 12 29 12Z" transform="rotate(270 48 48)"/>
  </svg>;
}
