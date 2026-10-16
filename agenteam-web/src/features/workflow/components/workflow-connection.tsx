"use client";

import {memo} from "react";
import {BaseEdge, EdgeLabelRenderer, getBezierPath, useViewport, type EdgeProps} from "@xyflow/react";
import {Button} from "@/components/ui/button";
import {IconTrash} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import type {WorkflowRoute} from "../lib/workflow-routing";
import styles from "./workflow.module.css";
import canvasStyles from "./workflow-canvas.module.css";

export const WorkflowConnection = memo(function WorkflowConnection(props: EdgeProps) {
  const text = useT();
  const {zoom} = useViewport();
  const remove = props.data?.onDelete as ((id: string) => void) | undefined;
  const routed = props.data?.route as WorkflowRoute | undefined;
  const [path, labelX, labelY] = routed ? [routed.path, routed.labelX, routed.labelY] : getBezierPath(props);
  return <><BaseEdge id={props.id} className={styles.connection} path={path} labelX={labelX} labelY={labelY} label={props.label}
    markerStart={props.markerStart} markerEnd={props.markerEnd} style={props.style} interactionWidth={24}
    labelStyle={props.labelStyle} labelBgStyle={props.labelBgStyle} labelBgPadding={[7, 4]} labelBgBorderRadius={7}/>
    {props.selected && remove && <EdgeLabelRenderer><div className={`${canvasStyles.edgeActions} nodrag nopan`}
      style={{transform: `translate(${labelX}px, ${labelY}px) scale(${1 / zoom}) translate(-50%, calc(-100% - ${14 + 56 * zoom}px))`}}>
      <span>{text("拖动端点调整连接")}</span>
      <Button type="button" className={canvasStyles.action} onClick={() => remove(props.id)}><IconTrash size={16}/>{text("删除连线")}</Button>
    </div></EdgeLabelRenderer>}
  </>;
});
