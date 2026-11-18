"use client";

import {useEffect, useRef, useState, type DragEvent} from "react";
import {droppedSources, type UploadSource} from "../lib/environment-upload-selection";

export function useEnvironmentFileDrop(enabled: boolean, onFiles: (files: UploadSource[]) => void) {
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);

  function clear() {
    depth.current = 0;
    setDragging(false);
  }

  useEffect(() => {
    if (!dragging) {
      return;
    }
    window.addEventListener("dragend", clear);
    window.addEventListener("drop", clear);
    window.addEventListener("blur", clear);
    return () => {
      window.removeEventListener("dragend", clear);
      window.removeEventListener("drop", clear);
      window.removeEventListener("blur", clear);
    };
  }, [dragging]);

  function accepts(event: DragEvent) {
    if (!event.dataTransfer.types.includes("Files")) {
      return false;
    }
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = enabled ? "copy" : "none";
    return enabled;
  }

  return {dragging: enabled && dragging, handlers: {
    onDragEnter(event: DragEvent) {
      if (accepts(event)) {
        depth.current++;
        setDragging(true);
      }
    },
    onDragOver(event: DragEvent) {
      accepts(event);
    },
    onDragLeave(event: DragEvent) {
      if (accepts(event)) {
        depth.current = Math.max(0, depth.current - 1);
        if (!depth.current) {
          clear();
        }
      }
    },
    onDrop(event: DragEvent) {
      if (!accepts(event)) {
        return;
      }
      clear();
      const selected = droppedSources(event.dataTransfer);
      if (selected.length) {
        onFiles(selected);
      }
    },
  }};
}
