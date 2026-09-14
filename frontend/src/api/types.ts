export interface TeacherProfile { teacherId: number; displayName: string; schoolName?: string }
export interface Classroom { id: number; classCode: string; name: string; grade?: number; semester?: string; studentCount: number }
export interface Student { id: number; classId: number; studentNo: string; name: string }
export interface KnowledgePoint { id: number; parentId?: number; code: string; name: string; grade: number; active: boolean }
export interface RubricItem { id?: number; orderNo: number; title: string; criteria: string; maxScore: number }
export interface Question {
  id: number; questionCode: string; type: 'SINGLE_CHOICE' | 'FILL_BLANK' | 'SOLUTION'; content: string
  standardAnswer?: string; totalScore: number; primaryKnowledgePointId: number; acceptedAnswers: string[]; rubricItems: RubricItem[]
}
export interface Assignment { id: number; classId: number; title: string; status: string; questionIds: number[] }
export interface ReviewQueueItem {
  resultId: number; answerId: number; studentNo: string; studentName: string; questionCode: string
  questionContent: string; answerContent: string; source: 'RULE' | 'AI'; suggestedScore: number; totalScore: number
  errorType: string; teacherExplanation?: string; studentFeedback?: string; scoreDetails: string
}
export interface KnowledgeMastery {
  knowledgePointId: number; code: string; name: string; earnedScore: number; possibleScore: number; masteryRatio: number; answerCount: number
}
export interface ErrorRanking { errorType: string; count: number }
