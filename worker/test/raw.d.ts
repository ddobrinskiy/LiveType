// Lets the tests load the real migration file instead of a copy of the schema
// that could silently drift from it. Vite serves `?raw` imports as strings.
declare module "*.sql?raw" {
  const content: string;
  export default content;
}
