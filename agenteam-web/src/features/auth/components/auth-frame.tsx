"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import Link from "next/link";
import {type ReactNode, useState} from "react";
import {IconArrowLeft, IconBook2, IconPlayerPause, IconPlayerPlay, IconRobot, IconSchema} from "@/components/ui/icons";
import {BrandMark} from "@/components/ui/brand-mark";
import {LanguageMenu} from "./language-menu";
import {LightRays} from "./light-rays";
import {ThemeMenu} from "./theme-menu";

export function AuthFrame({children, back = false, className = ""}: {
  children: ReactNode;
  back?: boolean;
  className?: string
}) {
  const uiText = useT();
  const [paused, setPaused] = useState(false);
  return <main className={`auth-page ${className}`}>
    <section className="auth-story" aria-label={uiText("AgenTeam 智能体工作平台")}>
      <LightRays paused={paused}/>
      <div className="auth-story-top">
        <div className="brand">
          <span className="brand-mark"><BrandMark size={32}/></span>
          <span className="brand-copy"><span
            className="brand-title">AgenTeam</span><small>{uiText("群策 · 智能体工作平台")}</small></span>
        </div>
        <Button className="auth-motion-control" aria-label={paused ? uiText("播放背景动效") : uiText("暂停背景动效")}
                aria-pressed={paused} onClick={() => setPaused(!paused)}>{paused ? <IconPlayerPlay size={17}/> :
          <IconPlayerPause size={17}/>}</Button></div>
      <div className="auth-story-content">
        <div className="auth-story-kicker">{uiText("为想法，留出更多可能")}</div>
        <h2>{uiText("让好想法，")}<br/><span>{uiText("成为做好的工作。")}</span></h2>
        <p>{uiText("认识你的数字员工，沉淀团队的方法。")}<br/>{uiText("从一个想法开始，一起把工作向前推进。")}</p>
        <div className="auth-features"><span><IconRobot size={20}
                                                        variant="Bulk"/>{uiText("数字员工")}</span><span><IconBook2
          size={20} variant="Bulk"/>{uiText("团队知识")}</span><span><IconSchema size={20}
                                                                                 variant="Bulk"/>{uiText("工作流")}</span>
        </div>
      </div>
      <div className="auth-story-bottom"><span>{uiText("把精力，留给更重要的事。")}</span><span>AgenTeam</span></div>
    </section>
    <section className="auth-form-side">
      <div className="auth-topbar">{back ?
        <Link className="text-button" href="/login"><IconArrowLeft size={17}/>{uiText("返回登录")}</Link> : <span/>}
        <div className="topbar-actions"><LanguageMenu/><ThemeMenu/></div>
      </div>
      <section className="auth-form-content">{children}</section>
    </section>
  </main>;
}
