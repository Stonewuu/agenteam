import type {DraftWrite} from "../types/resource";

type PlainObject = Record<string, unknown>;
const isObject = (value: unknown): value is PlainObject => value !== null && typeof value === "object" && !Array.isArray(value);
const ownValue = (value: PlainObject, key: string) => Object.prototype.hasOwnProperty.call(value, key) ? value[key] : undefined;

function equal(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) {
    return true;
  }
  if (Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length && left.every((value, index) => equal(value, right[index]));
  }
  if (!isObject(left) || !isObject(right)) {
    return false;
  }
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every((key) => Object.prototype.hasOwnProperty.call(right, key) && equal(left[key], right[key]));
}

function merge(original: unknown, edited: unknown, current: unknown): unknown {
  if (equal(original, edited)) {
    return structuredClone(current);
  }
  if (!isObject(original) || !isObject(edited) || !isObject(current)) {
    return structuredClone(edited);
  }
  const keys = new Set([...Object.keys(original), ...Object.keys(current), ...Object.keys(edited)]);
  return Object.fromEntries([...keys].flatMap((key) => {
    const value = merge(ownValue(original, key), ownValue(edited, key), ownValue(current, key));
    return value === undefined ? [] : [[key, value]];
  }));
}

/** 用户选择保留自己的修改时，只覆盖其实际修改的字段，其他字段使用最新保存内容。 */
export function mergeDraftChanges(original: DraftWrite, edited: DraftWrite, current: DraftWrite): DraftWrite {
  return merge(original, edited, current) as DraftWrite;
}
