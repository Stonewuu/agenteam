"use client";

import * as React from "react";
import {DayPicker, getDefaultClassNames, type DayButton, type RootProps, type ChevronProps} from "@daypicker/react";
import {cn} from "cn";
import {Button, buttonVariants} from "./button";
import {IconChevronDown, IconChevronLeft, IconChevronRight} from "../icons";

/** 基于 shadcn 的 base-nova Calendar，使用平台图标并保留日期按钮的焦点管理。 */
function Calendar({className, classNames, showOutsideDays = true, captionLayout = "label", components, ...props}: React.ComponentProps<typeof DayPicker>) {
  const defaults = getDefaultClassNames();
  return <DayPicker showOutsideDays={showOutsideDays} captionLayout={captionLayout}
    className={cn("group/calendar bg-background [--cell-size:--spacing(9)] in-data-[slot=popover-content]:bg-transparent", className)}
    classNames={{
      root: cn("w-fit", defaults.root),
      months: cn("relative flex flex-col gap-4 md:flex-row", defaults.months),
      month: cn("flex w-full flex-col", defaults.month),
      nav: cn("absolute inset-x-0 top-0 flex w-full items-center justify-between gap-1", defaults.nav),
      button_previous: cn(buttonVariants({variant: "ghost"}), "size-(--cell-size) p-0 aria-disabled:opacity-50", defaults.button_previous),
      button_next: cn(buttonVariants({variant: "ghost"}), "size-(--cell-size) p-0 aria-disabled:opacity-50", defaults.button_next),
      month_caption: cn("flex h-(--cell-size) w-full items-center justify-center px-(--cell-size)", defaults.month_caption),
      dropdowns: cn("flex items-center justify-center gap-1.5 text-sm font-medium", defaults.dropdowns),
      month_grid: cn("w-full border-collapse", defaults.month_grid),
      weekday: cn("text-sm font-normal text-muted-foreground", defaults.weekday),
      day: cn("group/day relative rounded-lg p-0 text-center", defaults.day),
      today: cn("rounded-lg bg-muted text-foreground", defaults.today),
      outside: cn("text-muted-foreground", defaults.outside),
      disabled: cn("opacity-50", defaults.disabled),
      hidden: cn("invisible", defaults.hidden),
      ...classNames,
    }}
    components={{
      Root: CalendarRoot,
      Chevron: CalendarChevron,
      DayButton: CalendarDayButton,
      ...components,
    }} {...props}/>;
}

function CalendarRoot({className, rootRef, ...props}: RootProps) {
  return <div ref={rootRef} data-slot="calendar" className={className} {...props}/>;
}

function CalendarChevron({className, orientation}: ChevronProps) {
  const Icon = orientation === "left" ? IconChevronLeft : orientation === "right" ? IconChevronRight : IconChevronDown;
  return <Icon size={17} className={className}/>;
}

function CalendarDayButton({className, day, modifiers, ...props}: React.ComponentProps<typeof DayButton>) {
  const ref = React.useRef<HTMLButtonElement>(null);
  React.useEffect(() => {
    if (modifiers.focused) {
      ref.current?.focus();
    }
  }, [modifiers.focused]);
  return <Button ref={ref} variant="ghost" size="icon" data-day={day.date.getDate()}
    data-selected-single={modifiers.selected && !modifiers.range_start && !modifiers.range_end && !modifiers.range_middle}
    data-range-start={modifiers.range_start} data-range-end={modifiers.range_end} data-range-middle={modifiers.range_middle}
    className={cn("relative flex w-full flex-col gap-1 rounded-lg leading-none font-normal data-[selected-single=true]:bg-primary data-[selected-single=true]:text-primary-foreground", className)}
    {...props}/>;
}

export {Calendar, CalendarDayButton};
