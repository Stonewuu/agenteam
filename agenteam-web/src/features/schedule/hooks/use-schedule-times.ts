"use client";

import {useEffect, useRef, useState} from "react";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ScheduleRule, ScheduleTimes} from "../types/schedule";

export function useScheduleTimes(enterpriseId: string, rule: ScheduleRule) {
  const {frequency, localDate, localTime, weekdays, monthDay, timezone} = rule;
  const serialized = JSON.stringify({frequency, localDate, localTime, weekdays, monthDay, timezone});
  const valid = Boolean(timezone.trim() && /^\d{2}:\d{2}$/.test(localTime) && (frequency !== "once" || /^\d{4}-\d{2}-\d{2}$/.test(localDate ?? "")) && (frequency !== "weekly" || weekdays.length) && (frequency !== "monthly" || monthDay));
  const [result, setResult] = useState<{ key: string; data: ScheduleTimes | null; error: string } | null>(null);
  const mutation = useRef(new ApiMutation());
  useEffect(() => {
    if (!valid) {
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      mutation.current.run<ScheduleTimes>(organizationPath(enterpriseId, "/schedules/preview-times"), {
        method: "POST",
        body: JSON.parse(serialized),
        signal: controller.signal
      })
        .then((data) => {
          if (!controller.signal.aborted) {
            setResult({key: serialized, data, error: ""});
          }
        })
        .catch((failure) => {
          if (!controller.signal.aborted) {
            console.error("读取计划执行时间失败", {enterpriseId}, failure);
            setResult({key: serialized, data: null, error: errorMessage(failure)});
          }
        });
    }, 500);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [enterpriseId, serialized, valid]);
  return {
    data: valid && result?.key === serialized ? result.data : null,
    error: valid && result?.key === serialized ? result.error : "",
    loading: valid && result?.key !== serialized,
    valid
  };
}
