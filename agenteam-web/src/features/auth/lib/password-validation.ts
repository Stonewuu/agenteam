export function passwordProblem(password: string, confirmation: string) {
  const length = Array.from(password).length;
  if (length < 12 || length > 128) {
    return "密码需要 12～128 个字符。";
  }
  if (password !== confirmation) {
    return "两次输入的密码不一致。";
  }
  return "";
}
