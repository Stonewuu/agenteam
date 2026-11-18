export type RuntimeSection = "execution" | "model" | "collaboration" | "environment" | "retention";

export type RuntimeSettings = {
  revision: string;
  values: Record<string, number>;
  bounds: Record<string, {minimum: number}>;
};
