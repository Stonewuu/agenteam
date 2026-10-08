"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Input} from "@/components/ui/shadcn/input";

import {type InputHTMLAttributes, useState} from "react";
import {IconEye, IconEyeOff} from "@/components/ui/icons";
import {useNativeFieldError} from "@/components/ui/use-native-field-error";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";

export function AuthInput({
                            type = "text",
                            passwordLabel: configuredPasswordLabel,
                            ...props
                          }: InputHTMLAttributes<HTMLInputElement> & {
  passwordLabel?: string;
}) {
  const uiText = useT();
  const passwordLabel = configuredPasswordLabel ?? uiText("密码");
  const [revealed, setRevealed] = useState(false);
  const validation = useNativeFieldError<HTMLInputElement>(props);
  const secret = type === "password";
  return (
    <><span className="auth-input-shell">
      <Input
        {...props}
        {...validation.control}
        aria-label={props["aria-label"] ?? (secret ? passwordLabel : validation.control["aria-label"])}
        type={secret && revealed ? "text" : type}
      />
      {secret && (
        <Button
          type="button"
          className="auth-password-toggle"
          aria-label={uiText(revealed ? "隐藏{0}" : "显示{0}", [passwordLabel])}
          aria-pressed={revealed}
          onClick={() => setRevealed(!revealed)}
        >
          {revealed ? <IconEyeOff size={19}/> : <IconEye size={19}/>}
        </Button>
      )}
    </span><FieldErrorFeedback id={validation.errorId} messages={validation.error ? [validation.error] : []}/></>
  );
}
