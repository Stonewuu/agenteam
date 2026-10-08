import type {SkillConfig} from "@/features/resource/types/resource";
import type {Employee} from "@/features/employee/types/employee";

export type SkillOption = {
  kind: "skill";
  resourceId: string;
  versionId: string;
  name: string;
  description: string;
  icon: string;
  color: string
};
export type WorkspaceSkill = { skill: SkillOption; employees: Employee[] };
export type SkillImportPreview = {
  previewToken: string; name: string; description: string; config: SkillConfig;
  unresolvedDependencies: { kind: "plugin" | "knowledge"; name: string }[]; expiresAt: string
};
